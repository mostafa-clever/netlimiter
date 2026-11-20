package com.wifiguard.limiter.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.random.Random

class Tun2NetForwarder(
    private val vpnService: VpnService,
    private val dnsHandler: DnsPacketHandler,
    private val tokenBucket: TokenBucket,
    private val tunOutput: FileOutputStream
) {

    data class PacketPolicyFlags(
        val blockAll: Boolean = false,
        val sinkholeEnabled: Boolean = true,
        val cacheEnabled: Boolean = true,
        val blockDohDot: Boolean = true,
        val blockQuicUdp443: Boolean = true
    )

    @Volatile
    var flags: PacketPolicyFlags = PacketPolicyFlags()

    private val connManager = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val uidBytesWindow = ConcurrentHashMap<Int, Long>()
    private val burstThrottledUntil = ConcurrentHashMap<Int, Long>()
    private val burstBucket = TokenBucket()
    @Volatile var burstThresholdBytesPerMin: Long = 50L * 1024L * 1024L
    @Volatile var burstGuardEnabled: Boolean = true
    @Volatile var burstCapMbps: Double = 1.0
    @Volatile private var lastWindowResetMs: Long = System.currentTimeMillis()

    private fun resolveOwnerUid(protocol: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching {
            val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
            val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            connManager.getConnectionOwnerUid(protocol, local, remote)
        }.getOrDefault(-1)
    }

    private fun recordUidBytesAndCheckBurst(uid: Int, bytes: Int) {
        if (uid <= 0 || !burstGuardEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastWindowResetMs > 60_000L) {
            uidBytesWindow.clear()
            lastWindowResetMs = now
        }
        val total = (uidBytesWindow[uid] ?: 0L) + bytes
        uidBytesWindow[uid] = total
        if (total > burstThresholdBytesPerMin) {
            burstThrottledUntil[uid] = now + 120_000L
            burstBucket.setRateMbps(burstCapMbps)
        }
    }

    private fun isUidBurstThrottled(uid: Int): Boolean {
        if (uid <= 0 || !burstGuardEnabled) return false
        val until = burstThrottledUntil[uid] ?: return false
        if (System.currentTimeMillis() > until) {
            burstThrottledUntil.remove(uid)
            return false
        }
        return true
    }

    private data class FlowKey(
        val protocol: Int,
        val srcIpInt: Int,
        val srcPort: Int,
        val dstIpInt: Int,
        val dstPort: Int
    )

    private enum class TcpState { SYN_SENT, ESTABLISHED, CLOSED }

    private class TcpSession(
        val key: FlowKey,
        val ownerUid: Int = -1,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val channel: SocketChannel,
        var clientSeq: Long,
        var mySeq: Long,
        var state: TcpState = TcpState.SYN_SENT,
        var lastActivityMs: Long = System.currentTimeMillis(),
        val writeQueue: ConcurrentLinkedQueue<ByteBuffer> = ConcurrentLinkedQueue()
    )

    private class UdpSession(
        val key: FlowKey,
        val ownerUid: Int = -1,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val channel: DatagramChannel,
        var lastActivityMs: Long = System.currentTimeMillis()
    )

    private val selector: Selector = Selector.open()
    private val tcpSessions = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udpSessions = ConcurrentHashMap<FlowKey, UdpSession>()
    private val pendingRegistrations = ConcurrentLinkedQueue<() -> Unit>()
    private val writeLock = Any()
    private val dnsExecutor = Executors.newFixedThreadPool(4)

    @Volatile
    private var running = true

    private val selectorThread = Thread {
        runCatching { runSelectorLoop() }
    }.apply {
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        name = "WifiGuard-Selector"
        isDaemon = true
        start()
    }

    fun close() {
        running = false
        runCatching { dnsExecutor.shutdownNow() }
        runCatching { selector.wakeup() }
        tcpSessions.values.forEach { runCatching { it.channel.close() } }
        udpSessions.values.forEach { runCatching { it.channel.close() } }
        tcpSessions.clear()
        udpSessions.clear()
    }

    fun processTunPacket(packet: ByteArray, length: Int) {
        if (!running || length < 20) return
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcIpInt = ipToInt(srcIp)
        val dstIpInt = ipToInt(dstIp)

        val currentFlags = flags

        when (protocol) {
            17 -> handleUdpFromTun(packet, length, ihl, srcIp, dstIp, srcIpInt, dstIpInt, currentFlags)
            6 -> handleTcpFromTun(packet, length, ihl, srcIp, dstIp, srcIpInt, dstIpInt, currentFlags)
        }
    }

    private fun handleUdpFromTun(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcIpInt: Int,
        dstIpInt: Int,
        currentFlags: PacketPolicyFlags
    ) {
        if (length < ihl + 8) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        if (dstPort == 53) {
            if (currentFlags.blockAll) return
            val pktCopy = packet.copyOf(length)
            dnsExecutor.execute {
                val resp = runCatching {
                    dnsHandler.handlePacket(
                        packet = pktCopy,
                        length = length,
                        sinkholeEnabled = currentFlags.sinkholeEnabled,
                        cacheEnabled = currentFlags.cacheEnabled,
                        blockDohDot = currentFlags.blockDohDot,
                        blockQuicUdp443 = currentFlags.blockQuicUdp443
                    )
                }.getOrNull()
                if (resp != null) writeToTun(resp)
            }
            return
        }

        if (currentFlags.blockAll) return
        if (dstPort == 443 && (currentFlags.blockQuicUdp443 || currentFlags.blockDohDot)) return
        if (dstPort == 853 && currentFlags.blockDohDot) return

        val payloadOffset = ihl + 8
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) return
        val payload = packet.copyOfRange(payloadOffset, length)

        val key = FlowKey(17, srcIpInt, srcPort, dstIpInt, dstPort)
        val existing = udpSessions[key]
        if (existing != null) {
            existing.lastActivityMs = System.currentTimeMillis()
            pendingRegistrations.add {
                runCatching { existing.channel.write(ByteBuffer.wrap(payload)) }
            }
            selector.wakeup()
            return
        }

        runCatching {
            val dc = DatagramChannel.open()
            vpnService.protect(dc.socket())
            dc.configureBlocking(false)
            dc.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort))
            val uid = resolveOwnerUid(17, srcIp, srcPort, dstIp, dstPort)
            val session = UdpSession(key, uid, srcIp, dstIp, dc)
            udpSessions[key] = session
            pendingRegistrations.add {
                runCatching {
                    dc.register(selector, SelectionKey.OP_READ, session)
                    dc.write(ByteBuffer.wrap(payload))
                }
            }
            selector.wakeup()
        }
    }

    private fun handleTcpFromTun(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcIpInt: Int,
        dstIpInt: Int,
        currentFlags: PacketPolicyFlags
    ) {
        if (length < ihl + 20) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val seqNum = readUInt32(packet, ihl + 4)
        val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0xF) * 4
        if (length < ihl + dataOffset) return

        val tcpFlags = packet[ihl + 13].toInt() and 0xFF
        val isSyn = (tcpFlags and 0x02) != 0
        val isAck = (tcpFlags and 0x10) != 0
        val isFin = (tcpFlags and 0x01) != 0
        val isRst = (tcpFlags and 0x04) != 0

        val payloadOffset = ihl + dataOffset
        val payloadLen = length - payloadOffset
        val key = FlowKey(6, srcIpInt, srcPort, dstIpInt, dstPort)

        if (currentFlags.blockAll) {
            if (isSyn || payloadLen > 0) {
                val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, seqNum + 1, 0x14, ByteArray(0))
                writeToTun(rstPkt)
            }
            return
        }

        if (currentFlags.blockDohDot && (dstPort == 853 || (dstPort == 443 && isPublicDnsIp(dstIp)))) {
            val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, seqNum + 1, 0x14, ByteArray(0))
            writeToTun(rstPkt)
            return
        }

        if (isSyn && !isAck) {
            val existingSyn = tcpSessions[key]
            if (existingSyn != null && existingSyn.state == TcpState.SYN_SENT) {
                return
            }
            tcpSessions.remove(key)?.let { old -> runCatching { old.channel.close() } }
            runCatching {
                val sc = SocketChannel.open()
                vpnService.protect(sc.socket())
                sc.configureBlocking(false)
                sc.socket().tcpNoDelay = true
                val connected = sc.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort))
                val initialMySeq = Random.nextLong(1000L, 0x7FFFFFFFL)
                val uid = resolveOwnerUid(6, srcIp, srcPort, dstIp, dstPort)
                val session = TcpSession(
                    key = key,
                    ownerUid = uid,
                    srcIp = srcIp,
                    dstIp = dstIp,
                    channel = sc,
                    clientSeq = (seqNum + 1) and 0xFFFFFFFFL,
                    mySeq = initialMySeq,
                    state = if (connected) TcpState.ESTABLISHED else TcpState.SYN_SENT
                )
                tcpSessions[key] = session
                pendingRegistrations.add {
                    runCatching {
                        if (connected) {
                            sendSynAck(session)
                            sc.register(selector, SelectionKey.OP_READ, session)
                        } else {
                            sc.register(selector, SelectionKey.OP_CONNECT, session)
                        }
                    }
                }
                selector.wakeup()
            }
            return
        }

        val session = tcpSessions[key] ?: return
        session.lastActivityMs = System.currentTimeMillis()

        if (isRst) {
            closeTcpSession(session)
            return
        }

        if (payloadLen > 0) {
            val payload = packet.copyOfRange(payloadOffset, length)
            val expected = session.clientSeq
            if (seqNum == expected) {
                session.clientSeq = (seqNum + payloadLen) and 0xFFFFFFFFL
                session.writeQueue.add(ByteBuffer.wrap(payload))
                val ackPkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    key.dstPort, key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x10, ByteArray(0)
                )
                writeToTun(ackPkt)
                pendingRegistrations.add {
                    runCatching {
                        val selKey = session.channel.keyFor(selector)
                        if (selKey != null && selKey.isValid && session.state == TcpState.ESTABLISHED) {
                            selKey.interestOps(selKey.interestOps() or SelectionKey.OP_WRITE)
                        }
                    }
                }
                selector.wakeup()
            } else {
                val ackPkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    key.dstPort, key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x10, ByteArray(0)
                )
                writeToTun(ackPkt)
            }
        }

        if (isFin) {
            session.clientSeq = (session.clientSeq + 1) and 0xFFFFFFFFL
            val finAck = buildTcpPacket(
                session.dstIp, session.srcIp,
                key.dstPort, key.srcPort,
                session.mySeq, session.clientSeq,
                0x11, ByteArray(0)
            )
            session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
            writeToTun(finAck)
            closeTcpSession(session)
        }
    }

    private fun runSelectorLoop() {
        val readBuf = ByteBuffer.allocate(1360)
        val udpBuf = ByteBuffer.allocate(1450)
        var lastCleanupMs = System.currentTimeMillis()

        try {
            while (running && selector.isOpen) {
                while (true) {
                    val task = pendingRegistrations.poll() ?: break
                    runCatching { task() }
                }

                val budget = tokenBucket.availableBytes()
                if (budget >= 256) {
                    for (session in tcpSessions.values) {
                        if (session.state == TcpState.ESTABLISHED) {
                            val k = runCatching { session.channel.keyFor(selector) }.getOrNull()
                            if (k != null && k.isValid && (k.interestOps() and SelectionKey.OP_READ) == 0) {
                                runCatching { k.interestOps(k.interestOps() or SelectionKey.OP_READ) }
                            }
                        }
                    }
                }

                if (!running || !selector.isOpen) break
                if (budget < 256) Thread.sleep(5L)
                runCatching { selector.select(10L) }
                if (!running || !selector.isOpen) break
                val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()
                if (!key.isValid) continue

                when (val att = key.attachment()) {
                    is TcpSession -> runCatching { handleTcpKey(key, att, readBuf) }.onFailure { closeTcpSession(att) }
                    is UdpSession -> runCatching { handleUdpKey(key, att, udpBuf) }.onFailure {
                        udpSessions.remove(att.key)
                        runCatching { att.channel.close() }
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastCleanupMs > 5_000L) {
                lastCleanupMs = now
                tcpSessions.values.filter {
                    (it.state == TcpState.SYN_SENT && now - it.lastActivityMs > 8_000L) ||
                    (now - it.lastActivityMs > 35_000L)
                }.forEach { closeTcpSession(it) }
                if (tcpSessions.size > 120) {
                    tcpSessions.values.sortedBy { it.lastActivityMs }.take(tcpSessions.size - 100).forEach { closeTcpSession(it) }
                }
                udpSessions.values.filter { now - it.lastActivityMs > 20_000L }.forEach {
                    udpSessions.remove(it.key)
                    runCatching { it.channel.close() }
                }
            }
        }
        } catch (_: Throwable) {
        } finally {
            runCatching { selector.close() }
        }
    }

    private fun handleTcpKey(key: SelectionKey, session: TcpSession, readBuf: ByteBuffer) {
        if (key.isConnectable) {
            val finished = runCatching { session.channel.finishConnect() }.getOrDefault(false)
            if (finished) {
                session.state = TcpState.ESTABLISHED
                sendSynAck(session)
                val ops = SelectionKey.OP_READ or if (session.writeQueue.isNotEmpty()) SelectionKey.OP_WRITE else 0
                key.interestOps(ops)
            } else {
                closeTcpSession(session)
            }
            return
        }

        if (key.isWritable) {
            while (true) {
                val buf = session.writeQueue.peek() ?: break
                val written = runCatching { session.channel.write(buf) }.getOrElse { -1 }
                if (written < 0) {
                    closeTcpSession(session)
                    return
                }
                if (buf.hasRemaining()) {
                    // Socket send buffer full, keep remaining ByteBuffer at head of queue
                    break
                } else {
                    session.writeQueue.poll()
                }
            }
            if (session.writeQueue.isEmpty() && key.isValid) {
                key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
            }
        }

        if (key.isReadable) {
            val uidThrottled = isUidBurstThrottled(session.ownerUid)
            val activeBucket = if (uidThrottled) burstBucket else tokenBucket
            val avail = activeBucket.availableBytes()
            if (avail < 256) {
                key.interestOps(key.interestOps() and SelectionKey.OP_READ.inv())
                return
            }

            readBuf.clear()
            if (avail < readBuf.capacity()) {
                readBuf.limit(avail.coerceAtLeast(256))
            }
            val bytesRead = runCatching { session.channel.read(readBuf) }.getOrElse { -1 }
            if (bytesRead < 0) {
                val finPkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    session.key.dstPort, session.key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x11, ByteArray(0)
                )
                writeToTun(finPkt)
                closeTcpSession(session)
                return
            }
            if (bytesRead > 0) {
                session.lastActivityMs = System.currentTimeMillis()
                tokenBucket.consume(bytesRead)
                if (uidThrottled) burstBucket.consume(bytesRead)
                recordUidBytesAndCheckBurst(session.ownerUid, bytesRead)
                val data = ByteArray(bytesRead)
                readBuf.flip()
                readBuf.get(data)
                val pkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    session.key.dstPort, session.key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x18,
                    data
                )
                session.mySeq = (session.mySeq + bytesRead) and 0xFFFFFFFFL
                writeToTun(pkt)
            }
        }
    }

    private fun handleUdpKey(key: SelectionKey, session: UdpSession, udpBuf: ByteBuffer) {
        if (!key.isReadable) return
        val uidThrottled = isUidBurstThrottled(session.ownerUid)
        val avail = (if (uidThrottled) burstBucket else tokenBucket).availableBytes()
        if (avail < 256) return

        udpBuf.clear()
        val read = runCatching { session.channel.read(udpBuf) }.getOrElse { -1 }
        if (read > 0) {
            session.lastActivityMs = System.currentTimeMillis()
            tokenBucket.consume(read)
            if (uidThrottled) burstBucket.consume(read)
            recordUidBytesAndCheckBurst(session.ownerUid, read)
            val data = ByteArray(read)
            udpBuf.flip()
            udpBuf.get(data)
            val pkt = buildUdpPacket(
                session.dstIp, session.srcIp,
                session.key.dstPort, session.key.srcPort,
                data
            )
            writeToTun(pkt)
        }
    }

    private fun sendSynAck(session: TcpSession) {
        val synAck = buildTcpPacket(
            session.dstIp, session.srcIp,
            session.key.dstPort, session.key.srcPort,
            session.mySeq, session.clientSeq,
            0x12,
            ByteArray(0)
        )
        session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
        writeToTun(synAck)
    }

    private fun closeTcpSession(session: TcpSession) {
        session.state = TcpState.CLOSED
        tcpSessions.remove(session.key)
        runCatching { session.channel.close() }
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching { tunOutput.write(packet) }
        }
    }

    private fun isPublicDnsIp(ip: ByteArray): Boolean {
        val s = "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
        return s == "1.1.1.1" || s == "1.0.0.1" || s == "8.8.8.8" || s == "8.8.4.4" || s == "9.9.9.9"
    }

    private fun ipToInt(ip: ByteArray): Int {
        return ((ip[0].toInt() and 0xFF) shl 24) or
            ((ip[1].toInt() and 0xFF) shl 16) or
            ((ip[2].toInt() and 0xFF) shl 8) or
            (ip[3].toInt() and 0xFF)
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)
    }

    private fun buildTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val totalLen = 20 + tcpLen
        val pkt = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(pkt)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)
        computeIpChecksum(pkt)

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seqNum.toInt())
        buf.putInt(ackNum.toInt())
        buf.put((5 shl 4).toByte())
        buf.put(flags.toByte())
        buf.putShort(65535.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(payload)

        computeTransportChecksum(pkt, 20, tcpLen, 6, srcIp, dstIp)
        return pkt
    }

    private fun buildUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(pkt)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)
        computeIpChecksum(pkt)

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
        buf.put(payload)

        return pkt
    }

    private fun computeIpChecksum(pkt: ByteArray) {
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val cs = (sum.inv() and 0xFFFF)
        pkt[10] = ((cs ushr 8) and 0xFF).toByte()
        pkt[11] = (cs and 0xFF).toByte()
    }

    private fun computeTransportChecksum(
        pkt: ByteArray,
        offset: Int,
        len: Int,
        protocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ) {
        var sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += protocol
        sum += len

        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (pkt[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val cs = sum.inv() and 0xFFFF
        pkt[offset + 16] = ((cs ushr 8) and 0xFF).toByte()
        pkt[offset + 17] = (cs and 0xFF).toByte()
    }
}
