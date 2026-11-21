package com.wifiguard.limiter.vpn

import android.net.VpnService
import com.wifiguard.limiter.model.isValidIpv4
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DnsPacketHandler(private val vpnService: VpnService) {

    private data class CachedDns(
        val responsePayload: ByteArray,
        val expiresAtMillis: Long
    )

    private val dnsCache = ConcurrentHashMap<String, CachedDns>()
    val blockedRequestsCount = AtomicLong(0L)
    @Volatile
    var upstreamDnsIp: String = "94.140.14.14"
        set(value) {
            if (field != value) {
                field = value
                dnsCache.clear()
            }
        }

    private val blockedDomainSuffixes = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "app-measurement.com",
        "crashlytics.com",
        "ads.facebook.com",
        "an.facebook.com",
        "analytics.tiktok.com",
        "ads.tiktok.com",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "mixpanel.com",
        "amplitude.com",
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "mopub.com",
        "unityads.unity3d.com",
        "applovin.com",
        "vungle.com",
        "ironsrc.com",
        "chartboost.com",
        "inmobi.com",
        "adcolony.com"
    )

    fun handlePacket(
        packet: ByteArray,
        length: Int,
        sinkholeEnabled: Boolean,
        cacheEnabled: Boolean,
        blockDohDot: Boolean,
        blockQuicUdp443: Boolean,
        stripAaaa: Boolean = false
    ): ByteArray? {
        if (length < 28 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8) return null

        val ipTotalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (ipTotalLen < ihl + 8 || ipTotalLen > length) return null

        // Drop fragmented IPv4 packets
        val fragFlagsAndOffset = ((packet[6].toInt() and 0x3F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragFlagsAndOffset != 0) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // Only UDP handled here

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        if (udpLen < 8 || ihl + udpLen > ipTotalLen) return null

        // Drop QUIC (UDP 443) or DoT/DoH UDP bypasses
        if (dstPort == 443 && (blockQuicUdp443 || blockDohDot)) {
            return null
        }
        if (dstPort == 853 && blockDohDot) {
            return null
        }

        if (dstPort != 53) return null

        val dnsOffset = ihl + 8
        val dnsEnd = ihl + udpLen
        val dnsLen = dnsEnd - dnsOffset
        if (dnsLen < 12) return null

        val dnsPayload = packet.copyOfRange(dnsOffset, dnsEnd)
        val domain = extractQueryDomain(dnsPayload)?.trimEnd('.')?.lowercase()
        val qType = extractQueryType(dnsPayload)

        // 1. Local DNS Sinkhole (type-aware: 0.0.0.0 for A, :: for AAAA, NXDOMAIN for others)
        if (sinkholeEnabled && domain != null && isBlockedDomain(domain)) {
            blockedRequestsCount.incrementAndGet()
            val sinkholeDns = buildSinkholeResponse(dnsPayload, qType) ?: return null
            return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, sinkholeDns)
        }

        // 2. Return empty NOERROR for AAAA queries ONLY when IPv4-only legacy tunnel is active
        if (stripAaaa && qType == 28) {
            val emptyResp = buildEmptyNoErrorResponse(dnsPayload) ?: return null
            return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, emptyResp)
        }

        if (!cacheEnabled && dnsCache.isNotEmpty()) {
            dnsCache.clear()
        }

        // 3. DNS Cache (keyed by upstreamDnsIp + domain + qType; caches valid non-truncated NOERROR for 5 min)
        val cacheKey = "$upstreamDnsIp:${domain ?: ""}:$qType"
        val now = System.currentTimeMillis()
        if (cacheEnabled && domain != null) {
            val cached = dnsCache[cacheKey]
            if (cached != null && cached.expiresAtMillis > now) {
                val resp = cached.responsePayload.copyOf()
                resp[0] = dnsPayload[0]
                resp[1] = dnsPayload[1]
                return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, resp)
            }
        }

        // 4. Forward to upstream DNS via protected socket
        val upstreamResp = forwardDnsQuery(dnsPayload) ?: return null
        if (cacheEnabled && domain != null && upstreamResp.size >= 12) {
            val flagsHi = upstreamResp[2].toInt() and 0xFF
            val flagsLo = upstreamResp[3].toInt() and 0xFF
            val isTruncated = (flagsHi and 0x02) != 0
            val rcode = flagsLo and 0x0F
            if (rcode == 0 && !isTruncated) {
                dnsCache[cacheKey] = CachedDns(
                    responsePayload = upstreamResp.copyOf(),
                    expiresAtMillis = now + 5L * 60L * 1000L
                )
                if (dnsCache.size > 500) {
                    val expiredKeys = dnsCache.entries.filter { it.value.expiresAtMillis <= now }.map { it.key }
                    expiredKeys.forEach { dnsCache.remove(it) }
                    if (dnsCache.size > 500) dnsCache.clear()
                }
            }
        }

        return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, upstreamResp)
    }

    private fun isBlockedDomain(domain: String): Boolean {
        return blockedDomainSuffixes.any { suffix ->
            domain == suffix || domain.endsWith(".$suffix")
        }
    }

    /**
     * Walks a QNAME starting at [startPos], handling compression pointers safely without infinite loops.
     * Returns the index immediately after the QNAME, or -1 if malformed.
     */
    private fun findQNameEnd(dns: ByteArray, startPos: Int = 12): Int {
        var pos = startPos
        var steps = 0
        while (pos < dns.size && steps < 128) {
            steps++
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            val typeBits = len and 0xC0
            if (typeBits == 0xC0) {
                // Compression pointer consumes 2 bytes and terminates QNAME
                return if (pos + 2 <= dns.size) pos + 2 else -1
            }
            if (typeBits != 0) return -1 // Invalid label type
            pos += 1 + len
        }
        return -1
    }

    private fun extractQueryDomain(dns: ByteArray): String? {
        return runCatching {
            var pos = 12
            val sb = StringBuilder()
            var jumps = 0
            while (pos < dns.size && jumps < 10) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                val typeBits = len and 0xC0
                if (typeBits == 0xC0) {
                    if (pos + 1 >= dns.size) return null
                    val ptr = ((len and 0x3F) shl 8) or (dns[pos + 1].toInt() and 0xFF)
                    if (ptr < 12 || ptr >= dns.size) return null
                    pos = ptr
                    jumps++
                    continue
                }
                if (typeBits != 0) return null
                pos++
                if (pos + len > dns.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, pos, len, Charsets.US_ASCII))
                pos += len
            }
            if (sb.isEmpty()) null else sb.toString()
        }.getOrNull()
    }

    private fun extractQueryType(dns: ByteArray): Int {
        return runCatching {
            val qNameEnd = findQNameEnd(dns, 12)
            if (qNameEnd < 0 || qNameEnd + 2 > dns.size) return 1
            ((dns[qNameEnd].toInt() and 0xFF) shl 8) or (dns[qNameEnd + 1].toInt() and 0xFF)
        }.getOrDefault(1)
    }

    private fun buildEmptyNoErrorResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val qNameEnd = findQNameEnd(query, 12)
        if (qNameEnd < 0) return null
        val questionEnd = qNameEnd + 4
        if (questionEnd > query.size) return null
        val out = ByteBuffer.allocate(questionEnd)
        out.put(query[0])
        out.put(query[1])
        out.putShort(0x8180.toShort())
        out.putShort(1)
        out.putShort(0)
        out.putShort(0)
        out.putShort(0)
        out.put(query, 12, questionEnd - 12)
        return out.array()
    }

    private fun buildSinkholeResponse(query: ByteArray, qType: Int): ByteArray? {
        if (query.size < 12) return null
        val qNameEnd = findQNameEnd(query, 12)
        if (qNameEnd < 0) return null
        val questionEnd = qNameEnd + 4 // QTYPE (2) + QCLASS (2)
        if (questionEnd > query.size) return null

        // For non-A/AAAA queries (e.g., HTTPS/65, TXT, SRV), return clean NXDOMAIN
        if (qType != 1 && qType != 28) {
            val out = ByteBuffer.allocate(questionEnd)
            out.put(query[0])
            out.put(query[1])
            out.putShort(0x8183.toShort()) // Standard response, NXDOMAIN
            out.putShort(1)
            out.putShort(0)
            out.putShort(0)
            out.putShort(0)
            out.put(query, 12, questionEnd - 12)
            return out.array()
        }

        val rdataLen = if (qType == 28) 16 else 4
        val out = ByteBuffer.allocate(questionEnd + 12 + rdataLen)
        out.put(query[0])
        out.put(query[1])
        out.putShort(0x8180.toShort())
        out.putShort(1)
        out.putShort(1)
        out.putShort(0)
        out.putShort(0)
        out.put(query, 12, questionEnd - 12)
        out.putShort(0xC00C.toShort())
        out.putShort(qType.toShort())
        out.putShort(1) // CLASS IN
        out.putInt(300) // TTL 300s
        out.putShort(rdataLen.toShort())
        out.put(ByteArray(rdataLen)) // 0.0.0.0 or ::
        return out.array()
    }

    private fun parseIpv4Address(ip: String): InetAddress {
        val safeIp = if (isValidIpv4(ip)) ip.trim() else "94.140.14.14"
        val parts = safeIp.split(".")
        val bytes = ByteArray(4) { i -> (parts[i].toInt() and 0xFF).toByte() }
        return InetAddress.getByAddress(bytes)
    }

    private fun forwardDnsQuery(query: ByteArray): ByteArray? {
        return runCatching {
            val socket = DatagramSocket()
            try {
                if (!vpnService.protect(socket)) {
                    return null
                }
                socket.soTimeout = 3000
                val upstream = parseIpv4Address(upstreamDnsIp)
                val outPacket = DatagramPacket(query, query.size, upstream, 53)
                socket.send(outPacket)

                val buf = ByteArray(4096)
                val inPacket = DatagramPacket(buf, buf.size)
                socket.receive(inPacket)
                if (inPacket.length > 1372) {
                    // Signal TC=1 (Truncated) so client resolver retries cleanly over TCP instead of corrupting packet
                    buildTruncatedResponse(query)
                } else {
                    buf.copyOf(inPacket.length)
                }
            } finally {
                runCatching { socket.close() }
            }
        }.getOrNull()
    }

    private fun buildTruncatedResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val qNameEnd = findQNameEnd(query, 12)
        if (qNameEnd < 0) return null
        val questionEnd = qNameEnd + 4
        if (questionEnd > query.size) return null
        val out = ByteBuffer.allocate(questionEnd)
        out.put(query[0])
        out.put(query[1])
        out.putShort(0x8380.toShort()) // QR=1, TC=1, RD=1, RA=1, NOERROR
        out.putShort(1)
        out.putShort(0)
        out.putShort(0)
        out.putShort(0)
        out.put(query, 12, questionEnd - 12)
        return out.array()
    }

    private fun wrapIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val pkt = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(pkt)

        // IPv4 Header
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

        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val ipChecksum = (sum.inv() and 0xFFFF).toShort()
        pkt[10] = ((ipChecksum.toInt() ushr 8) and 0xFF).toByte()
        pkt[11] = (ipChecksum.toInt() and 0xFF).toByte()

        val udpLen = 8 + payload.size
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
        buf.put(payload)

        return pkt
    }
}
