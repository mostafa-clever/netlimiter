package com.wifiguard.limiter.vpn

import android.net.VpnService
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
        blockQuicUdp443: Boolean
    ): ByteArray? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // Only UDP handled here

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        // Drop QUIC (UDP 443) or DoT/DoH UDP bypasses
        if (dstPort == 443 && (blockQuicUdp443 || blockDohDot)) {
            return null
        }
        if (dstPort == 853 && blockDohDot) {
            return null
        }

        if (dstPort != 53) return null

        val dnsOffset = ihl + 8
        val dnsLen = length - dnsOffset
        if (dnsLen < 12) return null

        val dnsPayload = packet.copyOfRange(dnsOffset, length)
        val domain = extractQueryDomain(dnsPayload)?.lowercase()

        // 1. Local DNS Sinkhole (0.0.0.0)
        if (sinkholeEnabled && domain != null && isBlockedDomain(domain)) {
            blockedRequestsCount.incrementAndGet()
            val sinkholeDns = buildSinkholeResponse(dnsPayload) ?: return null
            return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, sinkholeDns)
        }

        // 2. Aggressive 30-min DNS Cache
        val qType = extractQueryType(dnsPayload)
        val cacheKey = "${domain ?: ""}:$qType"
        val now = System.currentTimeMillis()
        if (cacheEnabled && domain != null) {
            val cached = dnsCache[cacheKey]
            if (cached != null && cached.expiresAtMillis > now) {
                val resp = cached.responsePayload.copyOf()
                // Copy incoming Transaction ID (first 2 bytes)
                resp[0] = dnsPayload[0]
                resp[1] = dnsPayload[1]
                return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, resp)
            }
        }

        // 3. Forward to upstream DNS (8.8.8.8:53) via protected socket
        val upstreamResp = forwardDnsQuery(dnsPayload) ?: return null
        if (cacheEnabled && domain != null && upstreamResp.size >= 12) {
            dnsCache[cacheKey] = CachedDns(
                responsePayload = upstreamResp.copyOf(),
                expiresAtMillis = now + 30L * 60L * 1000L // 30 minutes
            )
            if (dnsCache.size > 500) {
                dnsCache.clear()
            }
        }

        return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, upstreamResp)
    }

    private fun isBlockedDomain(domain: String): Boolean {
        return blockedDomainSuffixes.any { suffix ->
            domain == suffix || domain.endsWith(".$suffix")
        }
    }

    private fun extractQueryDomain(dns: ByteArray): String? {
        return runCatching {
            var pos = 12
            val sb = StringBuilder()
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) != 0) break
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
            var pos = 12
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                pos++
                if (len == 0) break
                pos += len
            }
            if (pos + 2 <= dns.size) {
                ((dns[pos].toInt() and 0xFF) shl 8) or (dns[pos + 1].toInt() and 0xFF)
            } else 1
        }.getOrDefault(1)
    }

    private fun buildSinkholeResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        // Find end of Question section
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            pos += len
        }
        val questionEnd = pos + 4 // QTYPE (2) + QCLASS (2)
        if (questionEnd > query.size) return null

        val out = ByteBuffer.allocate(questionEnd + 16)
        // Header: copy ID
        out.put(query[0])
        out.put(query[1])
        // Flags: Standard response, No error (0x8180)
        out.putShort(0x8180.toShort())
        // QDCOUNT = 1, ANCOUNT = 1, NSCOUNT = 0, ARCOUNT = 0
        out.putShort(1)
        out.putShort(1)
        out.putShort(0)
        out.putShort(0)
        // Question section
        out.put(query, 12, questionEnd - 12)
        // Answer section: pointer to QNAME (0xC00C)
        out.putShort(0xC00C.toShort())
        out.putShort(1) // TYPE A
        out.putShort(1) // CLASS IN
        out.putInt(300) // TTL 300s
        out.putShort(4) // RDLENGTH 4
        out.put(byteArrayOf(0, 0, 0, 0)) // 0.0.0.0

        return out.array()
    }

    private fun forwardDnsQuery(query: ByteArray): ByteArray? {
        return runCatching {
            val socket = DatagramSocket()
            try {
                vpnService.protect(socket)
                socket.soTimeout = 3000
                val upstream = InetAddress.getByName("8.8.8.8")
                val outPacket = DatagramPacket(query, query.size, upstream, 53)
                socket.send(outPacket)

                val buf = ByteArray(1500)
                val inPacket = DatagramPacket(buf, buf.size)
                socket.receive(inPacket)
                buf.copyOf(inPacket.length)
            } finally {
                socket.close()
            }
        }.getOrNull()
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
        buf.put(0x45.toByte()) // Version 4, IHL 5
        buf.put(0x00.toByte()) // TOS
        buf.putShort(totalLen.toShort())
        buf.putShort(0) // Identification
        buf.putShort(0x4000.toShort()) // Don't Fragment
        buf.put(64.toByte()) // TTL
        buf.put(17.toByte()) // Protocol UDP
        buf.putShort(0) // Header Checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)

        // Compute IPv4 header checksum
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

        // UDP Header
        val udpLen = 8 + payload.size
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // UDP checksum optional for IPv4
        buf.put(payload)

        return pkt
    }
}
