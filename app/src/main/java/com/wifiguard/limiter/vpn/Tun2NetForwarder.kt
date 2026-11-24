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
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class Tun2NetForwarder(
    private val vpnService: VpnService,
    private val dnsHandler: DnsPacketHandler,
    private val tokenBucket: TokenBucket,
    private val tunOutput: FileOutputStream,
    private val onFatalExit: (() -> Unit)? = null
) {

    data class PacketPolicyFlags(
        val blockAll: Boolean = false,
        val sinkholeEnabled: Boolean = true,
        val cacheEnabled: Boolean = true,
        val blockDohDot: Boolean = true,
        val blockQuicUdp443: Boolean = true,
        val mediaCdnSaverEnabled: Boolean = true,
        val blockAppUpdates: Boolean = true,
        val screenOffFreezeActive: Boolean = false,
        val backgroundFirewallEnabled: Boolean = false
    )

    private enum class FlowClass { NORMAL, MEDIA_CDN }

    @Volatile
    var flags: PacketPolicyFlags = PacketPolicyFlags()

    // Dedicated ABR media bucket (default 0.75 Mbps forces 360p-480p on YouTube/TikTok/Reels/Netflix)
    val mediaTokenBucket = TokenBucket()

    // Foreground-only firewall & screen-off freeze state (zero VPN teardown/rebuild needed!)
    @Volatile var foregroundUid: Int = -1
    @Volatile var whitelistedUids: Set<Int> = emptySet()

    // Honest real telemetry counters
    val blockedQuicCount = AtomicLong(0L)
    val blockedBackgroundFlowsCount = AtomicLong(0L)
    val blockedUpdateFlowsCount = AtomicLong(0L)
    val mediaThrottledBytes = AtomicLong(0L)

    private val connManager = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val uidBytesWindow = ConcurrentHashMap<Int, Long>()
    private val burstThrottledUntil = ConcurrentHashMap<Int, Long>()
    private val burstBucket = TokenBucket()
    @Volatile var burstThresholdBytesPerMin: Long = 50L * 1024L * 1024L
    @Volatile var burstGuardEnabled: Boolean = true
    @Volatile var burstCapMbps: Double = 1.0
    @Volatile private var lastWindowResetMs: Long = System.currentTimeMillis()

    companion object {
        private val MEDIA_CDN_SUFFIXES: Set<String> = hashSetOf(
            "googlevideo.com",
            "ytimg.com",
            "tiktokcdn.com",
            "tiktokcdn-us.com",
            "tiktokcdn-eu.com",
            "tiktokv.com",
            "byteoversea.com",
            "ibytedtos.com",
            "muscdn.com",
            "fbcdn.net",
            "cdninstagram.com",
            "twimg.com",
            "vimeocdn.com",
            "nflxvideo.net",
            "sc-cdn.net",
            "ttvnw.net",
            "dmcdn.net",
            "kwai.net",
            "kwimgs.com",
            "akamaized.net"
        )

        private val AUTO_UPDATE_HOSTS: Set<String> = hashSetOf(
            "gvt1.com",
            "gvt2.com",
            "ota.googlezip.net",
            "ota-cache1.googlezip.net",
            "ota-cache2.googlezip.net",
            "android.clients.google.com",
            "update.googleapis.com",
            "dl.google.com",
            "fota-cloud-dn.ospserver.net",
            "vas.samsungapps.com",
            "otacdn.coloros.com",
            "bigota.d.miui.com",
            "hugeota.d.miui.com",
            "update.miui.com"
        )

        private val DOH_HOSTS: Set<String> = hashSetOf(
            "dns.google",
            "dns.google.com",
            "cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.nextdns.io"
        )

        private fun matchesDomainSet(host: String, domainSet: Set<String>): Boolean {
            if (host in domainSet) return true
            var idx = host.indexOf('.')
            while (idx != -1) {
                if (host.substring(idx + 1) in domainSet) return true
                idx = host.indexOf('.', idx + 1)
            }
            return false
        }

        /**
         * Parses plaintext TLS ClientHello SNI hostname in O(extensions) with strict bounds checks.
         * Zero decryption, zero MITM.
         */
        fun extractTlsSni(payload: ByteArray): String? {
            if (payload.size < 45) return null
            if ((payload[0].toInt() and 0xFF) != 0x16) return null // TLS Handshake record
            if ((payload[5].toInt() and 0xFF) != 0x01) return null // ClientHello
            var pos = 43
            if (pos >= payload.size) return null
            val sessionIdLen = payload[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen
            if (pos + 2 > payload.size) return null
            val cipherSuitesLen = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen
            if (pos + 1 > payload.size) return null
            val compLen = payload[pos].toInt() and 0xFF
            pos += 1 + compLen
            if (pos + 2 > payload.size) return null
            val extTotalLen = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
            pos += 2
            val extEnd = minOf(payload.size, pos + extTotalLen)
            while (pos + 4 <= extEnd) {
                val extType = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                val extLen = ((payload[pos + 2].toInt() and 0xFF) shl 8) or (payload[pos + 3].toInt() and 0xFF)
                pos += 4
                if (pos + extLen > extEnd) break
                if (extType == 0 && extLen >= 5) {
                    val nameType = payload[pos + 2].toInt() and 0xFF
                    val nameLen = ((payload[pos + 3].toInt() and 0xFF) shl 8) or (payload[pos + 4].toInt() and 0xFF)
                    if (nameType == 0 && nameLen > 0 && pos + 5 + nameLen <= extEnd) {
                        return String(payload, pos + 5, nameLen, Charsets.US_ASCII).trim().trimEnd('.').lowercase()
                    }
                }
                pos += extLen
            }
            return null
        }
    }

    fun updateMediaRateMbps(enabled: Boolean, mediaSpeedMbps: Double) {
        if (enabled && mediaSpeedMbps > 0.0) {
            mediaTokenBucket.setRateMbps(mediaSpeedMbps)
        } else {
            mediaTokenBucket.setUnlimited()
        }
    }

    private fun resolveOwnerUid(protocol: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        val cm = connManager ?: return -1
        return runCatching {
            val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
            val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            cm.getConnectionOwnerUid(protocol, local, remote)
        }.getOrDefault(-1)
    }

    private fun isUidBlockedByFirewall(uid: Int, currentFlags: PacketPolicyFlags): Boolean {
        if (uid < 10_000) return false // Never block system/kernel/root UIDs (< Process.FIRST_APPLICATION_UID)
        if (uid in whitelistedUids) return false
        if (currentFlags.screenOffFreezeActive) return true
        if (currentFlags.backgroundFirewallEnabled && foregroundUid >= 10_000 && uid != foregroundUid) {
            return true
        }
        return false
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

    private fun computeAdvertisedWindow(session: TcpSession, currentFlags: PacketPolicyFlags): Int {
        if (session.flowClass == FlowClass.MEDIA_CDN && currentFlags.mediaCdnSaverEnabled && !mediaTokenBucket.isUnlimited()) {
            val rate = mediaTokenBucket.getRateBytesPerSec()
            return when {
                rate <= 80_000L -> 4096   // <= 0.64 Mbps -> locks ABR to 360p
                rate <= 130_000L -> 6144  // <= 1.04 Mbps -> locks ABR to 480p
                rate <= 250_000L -> 12288 // <= 2.0 Mbps
                else -> 32768
            }
        }
        if (!tokenBucket.isUnlimited()) {
            val rate = tokenBucket.getRateBytesPerSec()
            if (rate <= 150_000L) return 8192
            if (rate <= 300_000L) return 16384
        }
        return 65535
    }

    private data class FlowKey(
        val protocol: Int,
        val srcIpInt: Int,
        val srcPort: Int,
        val dstIpInt: Int,
        val dstPort: Int
    )

    private enum class TcpState { SYN_SENT, ESTABLISHED, CLIENT_FIN, SERVER_FIN, CLOSED }

    private class TcpSession(
        val key: FlowKey,
        val ownerUid: Int = -1,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val channel: SocketChannel,
        var clientSeq: Long,
        var mySeq: Long,
        var state: TcpState = TcpState.SYN_SENT,
        var flowClass: FlowClass = FlowClass.NORMAL,
        var sniInspected: Boolean = false,
        var hitMediaBackpressure: Boolean = false,
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
    private val pendingTasks = ConcurrentLinkedQueue<() -> Unit>()
    private val writeLock = Any()
    private val dnsExecutor = Executors.newFixedThreadPool(2)

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
        pendingTasks.clear()
    }

    fun closeNonWhitelistedSessions() {
        pendingTasks.add {
            val curFlags = flags
            tcpSessions.values.filter { isUidBlockedByFirewall(it.ownerUid, curFlags) }.forEach { s ->
                val rst = buildTcpPacket(s.dstIp, s.srcIp, s.key.dstPort, s.key.srcPort, s.mySeq, s.clientSeq, 0x14, ByteArray(0), 0)
                writeToTun(rst)
                closeTcpSession(s)
            }
            udpSessions.values.filter { isUidBlockedByFirewall(it.ownerUid, curFlags) }.forEach { s ->
                udpSessions.remove(s.key)
                runCatching { s.channel.close() }
            }
        }
        selector.wakeup()
    }

    fun processTunPacket(packet: ByteArray, length: Int) {
        if (!running || length < 20 || length > packet.size) return
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl) return

        val ipTotalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (ipTotalLen < ihl || ipTotalLen > length) return

        val fragField = ((packet[6].toInt() and 0x3F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragField != 0) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcIpInt = ipToInt(srcIp)
        val dstIpInt = ipToInt(dstIp)

        val currentFlags = flags

        when (protocol) {
            17 -> handleUdpFromTun(packet, ipTotalLen, ihl, srcIp, dstIp, srcIpInt, dstIpInt, currentFlags)
            6 -> handleTcpFromTun(packet, ipTotalLen, ihl, srcIp, dstIp, srcIpInt, dstIpInt, currentFlags)
        }
    }

    private fun handleUdpFromTun(
        packet: ByteArray,
        ipTotalLen: Int,
        ihl: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcIpInt: Int,
        dstIpInt: Int,
        currentFlags: PacketPolicyFlags
    ) {
        if (ipTotalLen < ihl + 8) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        if (udpLen < 8 || ihl + udpLen > ipTotalLen) return

        if (dstPort == 53) {
            if (currentFlags.blockAll) return
            val pktCopy = packet.copyOf(ipTotalLen)
            runCatching {
                dnsExecutor.execute {
                    val resp = runCatching {
                        dnsHandler.handlePacket(
                            packet = pktCopy,
                            length = ipTotalLen,
                            sinkholeEnabled = currentFlags.sinkholeEnabled,
                            cacheEnabled = currentFlags.cacheEnabled,
                            blockDohDot = currentFlags.blockDohDot,
                            blockQuicUdp443 = currentFlags.blockQuicUdp443,
                            stripAaaa = true,
                            blockAppUpdates = currentFlags.blockAppUpdates
                        )
                    }.getOrNull()
                    if (resp != null) writeToTun(resp)
                }
            }
            return
        }

        if (currentFlags.blockAll) return

        // Hard QUIC (UDP 443) drop -> forces YouTube, TikTok, Instagram, Chrome cleanly onto TCP/TLS 443
        if (dstPort == 443 && (currentFlags.blockQuicUdp443 || currentFlags.blockDohDot)) {
            blockedQuicCount.incrementAndGet()
            return
        }
        if (dstPort == 853 && currentFlags.blockDohDot) return

        val payloadOffset = ihl + 8
        val payloadEnd = ihl + udpLen
        if (payloadEnd <= payloadOffset) return
        val payload = packet.copyOfRange(payloadOffset, payloadEnd)

        val key = FlowKey(17, srcIpInt, srcPort, dstIpInt, dstPort)
        pendingTasks.add {
            val existing = udpSessions[key]
            if (existing != null) {
                if (isUidBlockedByFirewall(existing.ownerUid, flags)) {
                    udpSessions.remove(key)
                    runCatching { existing.channel.close() }
                    return@add
                }
                existing.lastActivityMs = System.currentTimeMillis()
                runCatching { existing.channel.write(ByteBuffer.wrap(payload)) }
                return@add
            }

            val uid = resolveOwnerUid(17, srcIp, srcPort, dstIp, dstPort)
            if (isUidBlockedByFirewall(uid, flags)) {
                blockedBackgroundFlowsCount.incrementAndGet()
                return@add
            }

            var dc: DatagramChannel? = null
            try {
                dc = DatagramChannel.open()
                if (!vpnService.protect(dc.socket())) {
                    dc.close()
                    return@add
                }
                dc.configureBlocking(false)
                dc.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort))
                val session = UdpSession(key, uid, srcIp, dstIp, dc)
                dc.register(selector, SelectionKey.OP_READ, session)
                udpSessions[key] = session
                dc.write(ByteBuffer.wrap(payload))
            } catch (_: Throwable) {
                udpSessions.remove(key)
                runCatching { dc?.close() }
            }
        }
        selector.wakeup()
    }

    private fun handleTcpFromTun(
        packet: ByteArray,
        ipTotalLen: Int,
        ihl: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcIpInt: Int,
        dstIpInt: Int,
        currentFlags: PacketPolicyFlags
    ) {
        if (ipTotalLen < ihl + 20) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val seqNum = readUInt32(packet, ihl + 4)
        val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0xF) * 4
        if (dataOffset < 20 || ipTotalLen < ihl + dataOffset) return

        val tcpFlags = packet[ihl + 13].toInt() and 0xFF
        val isSyn = (tcpFlags and 0x02) != 0
        val isAck = (tcpFlags and 0x10) != 0
        val isFin = (tcpFlags and 0x01) != 0
        val isRst = (tcpFlags and 0x04) != 0

        val payloadOffset = ihl + dataOffset
        val payloadLen = ipTotalLen - payloadOffset
        val key = FlowKey(6, srcIpInt, srcPort, dstIpInt, dstPort)

        if (currentFlags.blockAll) {
            if (!isRst && (isSyn || payloadLen > 0)) {
                val rstAck = (seqNum + if (isSyn) 1L else payloadLen.toLong()) and 0xFFFFFFFFL
                val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, rstAck, 0x14, ByteArray(0), 0)
                writeToTun(rstPkt)
            }
            return
        }

        if (currentFlags.blockDohDot && (dstPort == 853 || (dstPort == 443 && isPublicDnsIp(dstIp)))) {
            if (!isRst) {
                val rstAck = (seqNum + if (isSyn) 1L else payloadLen.coerceAtLeast(1).toLong()) and 0xFFFFFFFFL
                val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, rstAck, 0x14, ByteArray(0), 0)
                writeToTun(rstPkt)
            }
            return
        }

        val payload = if (payloadLen > 0) packet.copyOfRange(payloadOffset, ipTotalLen) else ByteArray(0)

        pendingTasks.add {
            handleTcpOnSelector(key, srcIp, dstIp, srcPort, dstPort, seqNum, isSyn, isAck, isFin, isRst, payload)
        }
        selector.wakeup()
    }

    private fun handleTcpOnSelector(
        key: FlowKey,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        isSyn: Boolean,
        isAck: Boolean,
        isFin: Boolean,
        isRst: Boolean,
        payload: ByteArray
    ) {
        val currentFlags = flags

        if (isSyn && !isAck) {
            val existingSyn = tcpSessions[key]
            if (existingSyn != null && existingSyn.state == TcpState.SYN_SENT) return
            if (existingSyn != null) closeTcpSession(existingSyn)

            val uid = resolveOwnerUid(6, srcIp, srcPort, dstIp, dstPort)
            if (isUidBlockedByFirewall(uid, currentFlags)) {
                blockedBackgroundFlowsCount.incrementAndGet()
                val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, (seqNum + 1) and 0xFFFFFFFFL, 0x14, ByteArray(0), 0)
                writeToTun(rstPkt)
                return
            }

            var sc: SocketChannel? = null
            try {
                sc = SocketChannel.open()
                if (!vpnService.protect(sc.socket())) {
                    sc.close()
                    return
                }
                sc.configureBlocking(false)
                sc.socket().tcpNoDelay = true
                val connected = sc.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort))
                val initialMySeq = Random.nextLong(1000L, 0x7FFFFFFFL)
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
                if (connected) {
                    sc.register(selector, SelectionKey.OP_READ, session)
                    tcpSessions[key] = session
                    sendSynAck(session, currentFlags)
                } else {
                    sc.register(selector, SelectionKey.OP_CONNECT, session)
                    tcpSessions[key] = session
                }
            } catch (_: Throwable) {
                tcpSessions.remove(key)
                runCatching { sc?.close() }
                val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, (seqNum + 1) and 0xFFFFFFFFL, 0x14, ByteArray(0), 0)
                writeToTun(rstPkt)
            }
            return
        }

        val session = tcpSessions[key]
        if (session == null) {
            if (!isRst) {
                val rstAck = (seqNum + payload.size.coerceAtLeast(if (isFin) 1 else 0).toLong()) and 0xFFFFFFFFL
                val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, rstAck, 0x14, ByteArray(0), 0)
                writeToTun(rstPkt)
            }
            return
        }

        session.lastActivityMs = System.currentTimeMillis()

        if (isRst) {
            closeTcpSession(session)
            return
        }

        if (payload.isNotEmpty()) {
            if (seqNum == session.clientSeq) {
                // Inspect first TLS ClientHello packet for SNI classification (video ABR / updates / DoH)
                if (!session.sniInspected) {
                    session.sniInspected = true
                    val sni = if (key.dstPort == 443) extractTlsSni(payload) else null
                    if (sni != null) {
                        val blockUpdate = currentFlags.blockAppUpdates && matchesDomainSet(sni, AUTO_UPDATE_HOSTS)
                        val blockDoh = currentFlags.blockDohDot && matchesDomainSet(sni, DOH_HOSTS)
                        if (blockUpdate || blockDoh) {
                            if (blockUpdate) blockedUpdateFlowsCount.incrementAndGet()
                            val rstAck = (seqNum + payload.size) and 0xFFFFFFFFL
                            val rstPkt = buildTcpPacket(dstIp, srcIp, dstPort, srcPort, session.mySeq, rstAck, 0x14, ByteArray(0), 0)
                            writeToTun(rstPkt)
                            closeTcpSession(session)
                            return
                        }
                        if (currentFlags.mediaCdnSaverEnabled && matchesDomainSet(sni, MEDIA_CDN_SUFFIXES)) {
                            session.flowClass = FlowClass.MEDIA_CDN
                            // Clamp kernel SO_RCVBUF on remote socket so remote CDN naturally paces via TCP RWND
                            val rwnd = computeAdvertisedWindow(session, currentFlags)
                            runCatching { session.channel.socket().receiveBufferSize = rwnd * 2 }
                        }
                    }
                }

                session.clientSeq = (seqNum + payload.size) and 0xFFFFFFFFL
                session.writeQueue.add(ByteBuffer.wrap(payload))
                val win = computeAdvertisedWindow(session, currentFlags)
                val ackPkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    key.dstPort, key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x10, ByteArray(0), win
                )
                writeToTun(ackPkt)
                val selKey = runCatching { session.channel.keyFor(selector) }.getOrNull()
                if (selKey != null && selKey.isValid && session.state == TcpState.ESTABLISHED) {
                    runCatching { selKey.interestOps(selKey.interestOps() or SelectionKey.OP_WRITE) }
                }
            } else {
                val win = computeAdvertisedWindow(session, currentFlags)
                val ackPkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    key.dstPort, key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x10, ByteArray(0), win
                )
                writeToTun(ackPkt)
            }
        }

        if (isFin && seqNum + payload.size == session.clientSeq) {
            session.clientSeq = (session.clientSeq + 1) and 0xFFFFFFFFL
            val win = computeAdvertisedWindow(session, currentFlags)
            val ackPkt = buildTcpPacket(
                session.dstIp, session.srcIp,
                key.dstPort, key.srcPort,
                session.mySeq, session.clientSeq,
                0x10, ByteArray(0), win
            )
            writeToTun(ackPkt)
            session.state = TcpState.CLIENT_FIN
            if (session.writeQueue.isEmpty()) {
                runCatching { session.channel.shutdownOutput() }
            }
        }
    }

    private fun runSelectorLoop() {
        val readBuf = ByteBuffer.allocate(1360)
        val udpBuf = ByteBuffer.allocate(1372)
        var lastCleanupMs = System.currentTimeMillis()

        try {
            while (running && selector.isOpen) {
                while (true) {
                    val task = pendingTasks.poll() ?: break
                    runCatching { task() }
                }

                val currentFlags = flags
                var anyThrottled = false
                var minWaitMs = 120L

                for (session in tcpSessions.values) {
                    if (session.state == TcpState.ESTABLISHED || session.state == TcpState.CLIENT_FIN) {
                        val k = runCatching { session.channel.keyFor(selector) }.getOrNull()
                        if (k != null && k.isValid) {
                            val mediaLimited = session.flowClass == FlowClass.MEDIA_CDN &&
                                currentFlags.mediaCdnSaverEnabled && !mediaTokenBucket.isUnlimited()
                            val burstLimited = isUidBurstThrottled(session.ownerUid)
                            val gAvail = tokenBucket.availableBytes()
                            val mAvail = if (mediaLimited) mediaTokenBucket.availableBytes() else 65536
                            val bAvail = if (burstLimited) burstBucket.availableBytes() else 65536
                            val avail = minOf(gAvail, mAvail, bAvail)
                            if (avail >= 1360) {
                                if ((k.interestOps() and SelectionKey.OP_READ) == 0) {
                                    runCatching { k.interestOps(k.interestOps() or SelectionKey.OP_READ) }
                                }
                            } else {
                                anyThrottled = true
                                if (mediaLimited && mAvail < 1360) session.hitMediaBackpressure = true
                                val waitG = if (gAvail < 1360) tokenBucket.millisUntilBytes(4096) else 0L
                                val waitM = if (mediaLimited && mAvail < 1360) mediaTokenBucket.millisUntilBytes(4096) else 0L
                                val waitB = if (burstLimited && bAvail < 1360) burstBucket.millisUntilBytes(4096) else 0L
                                minWaitMs = minOf(minWaitMs, maxOf(waitG, waitM, waitB, 30L))
                                if ((k.interestOps() and SelectionKey.OP_READ) != 0) {
                                    runCatching { k.interestOps(k.interestOps() and SelectionKey.OP_READ.inv()) }
                                }
                            }
                        }
                    }
                }
                for (session in udpSessions.values) {
                    val activeBucket = if (isUidBurstThrottled(session.ownerUid)) burstBucket else tokenBucket
                    val k = runCatching { session.channel.keyFor(selector) }.getOrNull()
                    if (k != null && k.isValid) {
                        if (activeBucket.availableBytes() >= 512) {
                            if ((k.interestOps() and SelectionKey.OP_READ) == 0) {
                                runCatching { k.interestOps(k.interestOps() or SelectionKey.OP_READ) }
                            }
                        } else {
                            anyThrottled = true
                            minWaitMs = minOf(minWaitMs, activeBucket.millisUntilBytes(2048).coerceIn(30L, 120L))
                            if ((k.interestOps() and SelectionKey.OP_READ) != 0) {
                                runCatching { k.interestOps(k.interestOps() and SelectionKey.OP_READ.inv()) }
                            }
                        }
                    }
                }

                if (!running || !selector.isOpen) break

                // CRITICAL BATTERY SAVER: Block indefinitely (0ms timeout = 0 CPU wakeups!) when no active sockets exist
                if (tcpSessions.isEmpty() && udpSessions.isEmpty()) {
                    runCatching { selector.select(0L) }
                } else {
                    val selectTimeout = if (anyThrottled) minWaitMs.coerceIn(30L, 150L) else 2_000L
                    runCatching { selector.select(selectTimeout) }
                }
                if (!running || !selector.isOpen) break

                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid) continue

                    when (val att = key.attachment()) {
                        is TcpSession -> runCatching { handleTcpKey(key, att, readBuf, currentFlags) }.onFailure { closeTcpSession(att) }
                        is UdpSession -> runCatching { handleUdpKey(key, att, udpBuf) }.onFailure {
                            udpSessions.remove(att.key)
                            runCatching { att.channel.close() }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastCleanupMs > 10_000L && (tcpSessions.isNotEmpty() || udpSessions.isNotEmpty())) {
                    lastCleanupMs = now
                    tcpSessions.values.filter {
                        (it.state == TcpState.SYN_SENT && now - it.lastActivityMs > 8_000L) ||
                            ((it.state == TcpState.CLIENT_FIN || it.state == TcpState.SERVER_FIN) && now - it.lastActivityMs > 6_000L) ||
                            (now - it.lastActivityMs > 60_000L)
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
            val unexpectedExit = running
            runCatching { selector.close() }
            if (unexpectedExit) {
                runCatching { onFatalExit?.invoke() }
            }
        }
    }

    private fun handleTcpKey(key: SelectionKey, session: TcpSession, readBuf: ByteBuffer, currentFlags: PacketPolicyFlags) {
        if (key.isConnectable) {
            val finished = runCatching { session.channel.finishConnect() }.getOrDefault(false)
            if (finished) {
                session.state = TcpState.ESTABLISHED
                sendSynAck(session, currentFlags)
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
                    break
                } else {
                    session.writeQueue.poll()
                }
            }
            if (session.writeQueue.isEmpty() && key.isValid) {
                key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
                if (session.state == TcpState.CLIENT_FIN) {
                    runCatching { session.channel.shutdownOutput() }
                }
            }
        }

        if (key.isReadable) {
            val mediaLimited = session.flowClass == FlowClass.MEDIA_CDN &&
                currentFlags.mediaCdnSaverEnabled && !mediaTokenBucket.isUnlimited()
            val uidThrottled = isUidBurstThrottled(session.ownerUid)
            val avail = minOf(
                tokenBucket.availableBytes(),
                if (mediaLimited) mediaTokenBucket.availableBytes() else 65536,
                if (uidThrottled) burstBucket.availableBytes() else 65536
            )
            if (avail < 512) {
                key.interestOps(key.interestOps() and SelectionKey.OP_READ.inv())
                return
            }

            readBuf.clear()
            if (avail < readBuf.capacity()) {
                readBuf.limit(avail.coerceAtLeast(512))
            }
            val bytesRead = runCatching { session.channel.read(readBuf) }.getOrElse { -1 }
            if (bytesRead < 0) {
                val win = computeAdvertisedWindow(session, currentFlags)
                val finPkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    session.key.dstPort, session.key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x11, ByteArray(0), win
                )
                session.mySeq = (session.mySeq + 1) and 0xFFFFFFFFL
                writeToTun(finPkt)
                session.state = TcpState.SERVER_FIN
                session.lastActivityMs = System.currentTimeMillis()
                runCatching { key.interestOps(key.interestOps() and SelectionKey.OP_READ.inv()) }
                return
            }
            if (bytesRead > 0) {
                session.lastActivityMs = System.currentTimeMillis()
                tokenBucket.consume(bytesRead)
                if (mediaLimited) {
                    mediaTokenBucket.consume(bytesRead)
                    if (session.hitMediaBackpressure) {
                        mediaThrottledBytes.addAndGet(bytesRead.toLong())
                    }
                }
                if (uidThrottled) burstBucket.consume(bytesRead)
                recordUidBytesAndCheckBurst(session.ownerUid, bytesRead)
                val data = ByteArray(bytesRead)
                readBuf.flip()
                readBuf.get(data)
                val win = computeAdvertisedWindow(session, currentFlags)
                val pkt = buildTcpPacket(
                    session.dstIp, session.srcIp,
                    session.key.dstPort, session.key.srcPort,
                    session.mySeq, session.clientSeq,
                    0x18,
                    data,
                    win
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
        if (avail < 256) {
            key.interestOps(key.interestOps() and SelectionKey.OP_READ.inv())
            return
        }

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

    private fun sendSynAck(session: TcpSession, currentFlags: PacketPolicyFlags) {
        val win = computeAdvertisedWindow(session, currentFlags)
        val synAck = buildTcpPacket(
            session.dstIp, session.srcIp,
            session.key.dstPort, session.key.srcPort,
            session.mySeq, session.clientSeq,
            0x12,
            ByteArray(0),
            win
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
        payload: ByteArray,
        windowSize: Int = 65535
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
        buf.putShort(windowSize.coerceIn(0, 65535).toShort())
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
