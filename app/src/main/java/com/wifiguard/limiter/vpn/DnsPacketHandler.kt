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
    val blockedUpdateQueriesCount = AtomicLong(0L)
    val strippedHttpsQueriesCount = AtomicLong(0L)

    @Volatile
    var upstreamDnsIp: String = "94.140.14.14"
        set(value) {
            if (field != value) {
                field = value
                dnsCache.clear()
            }
        }

    // O(label-depth) HashSet for fast suffix lookup (~3-4 lookups per DNS query instead of O(N) list scan)
    private val blockedAdDomains: Set<String> = hashSetOf(
        // Google & YouTube Ads / Telemetry
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "app-measurement.com",
        "crashlytics.com",
        "firebase-settings.crashlytics.com",
        "firebaselogging-pa.googleapis.com",
        "admob.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        // Meta / Facebook / Instagram Ads & Analytics
        "ads.facebook.com",
        "an.facebook.com",
        "graph.instagram.com",
        "pixel.facebook.com",
        "analytics.facebook.com",
        // TikTok / ByteDance Ads & Telemetry
        "analytics.tiktok.com",
        "ads.tiktok.com",
        "ads-sg.tiktok.com",
        "log-va.tiktokv.com",
        "mon-va.tiktokv.com",
        "log.byteoversea.com",
        "maliva-mcs.byteoversea.com",
        "mcs-va.tiktokv.com",
        "mssdk-va.tiktokv.com",
        "p16-ad-sg.ibyteimg.com",
        // Mobile Ad Networks & Mediation SDKs
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "mixpanel.com",
        "amplitude.com",
        "appsflyer.com",
        "appsflyersdk.com",
        "adjust.com",
        "branch.io",
        "mopub.com",
        "unityads.unity3d.com",
        "applovin.com",
        "applvn.com",
        "vungle.com",
        "ironsrc.com",
        "supersonicads.com",
        "chartboost.com",
        "inmobi.com",
        "adcolony.com",
        "criteo.com",
        "criteo.net",
        "pubmatic.com",
        "openx.net",
        "rubiconproject.com",
        "adnxs.com",
        "casalemedia.com",
        "amazon-adsystem.com",
        "flurry.com",
        "branchmetrics.io",
        "braze.com",
        "onesignal.com",
        "clevertap-prod.com",
        "kochava.com",
        "singular.net",
        "startappservice.com",
        "pangle-ads.com",
        "pangolin-sdk-toutiao.com",
        "bytegoofy.com",
        "mintegral.com",
        "rayjump.com",
        "liftoff.io",
        "fyber.com",
        "tapjoy.com",
        "smaato.net",
        "yandex.ru",
        "appmetrica.yandex.ru",
        "ads.twitter.com",
        "analytics.twitter.com",
        "ads.snapchat.com",
        "sc-analytics.appspot.com",
        "sentry.io",
        "bugsnag.com",
        "newrelic.com",
        "segment.io",
        "segment.com",
        "hotjar.com",
        "fullstory.com",
        "quantserve.com",
        "doubleverify.com",
        "moatads.com",
        "adsafeprotected.com",
        "demdex.net",
        "omtrdc.net",
        "krxd.net",
        "bluekai.com",
        "exelator.com",
        "rlcdn.com",
        "smartadserver.com",
        "yieldmo.com",
        "sharethrough.com",
        "triplelift.com",
        "indexww.com",
        "33across.com",
        "bidswitch.net",
        "liadm.com",
        "id5-sync.com",
        "tapad.com",
        "agkn.com",
        "mathtag.com",
        "rfihub.com",
        "turn.com",
        "adform.net",
        "serving-sys.com",
        "mediavine.com",
        "adthrive.com",
        // OEM Telemetry
        "tracking.miui.com",
        "data.mistat.xiaomi.com",
        "sdkconfig.ad.xiaomi.com",
        "metrics.data.hicloud.com",
        "logservice.hicloud.com",
        "samsungacr.com",
        "samsungcloudsolution.com",
        "smetrics.samsung.com",
        "nmetrics.samsung.com",
        "ads.heytapmobi.com"
    )

    private val blockedUpdateDomains: Set<String> = hashSetOf(
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
        "update.miui.com",
        "query.hicloud.com",
        "update.dbankcdn.com"
    )

    private val blockedDohDomains: Set<String> = hashSetOf(
        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "dns.quad9.net",
        "doh.opendns.com",
        "dns.nextdns.io",
        "doh.cleanbrowsing.org",
        "doh.dns.sb"
    )

    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L // 30 minutes to minimize radio wakeups
        private const val MAX_CACHE_ENTRIES = 1000
    }

    private fun matchesDomainSet(domain: String, domainSet: Set<String>): Boolean {
        if (domain in domainSet) return true
        var idx = domain.indexOf('.')
        while (idx != -1) {
            val parent = domain.substring(idx + 1)
            if (parent in domainSet) return true
            idx = domain.indexOf('.', idx + 1)
        }
        return false
    }

    fun handlePacket(
        packet: ByteArray,
        length: Int,
        sinkholeEnabled: Boolean,
        cacheEnabled: Boolean,
        blockDohDot: Boolean,
        blockQuicUdp443: Boolean,
        stripAaaa: Boolean = false,
        blockAppUpdates: Boolean = false
    ): ByteArray? {
        if (length < 28 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8) return null

        val ipTotalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (ipTotalLen < ihl + 8 || ipTotalLen > length) return null

        val fragFlagsAndOffset = ((packet[6].toInt() and 0x3F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragFlagsAndOffset != 0) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        if (udpLen < 8 || ihl + udpLen > ipTotalLen) return null

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

        // 1. Strip HTTPS/SVCB (qType=65) when QUIC blocking is enabled so apps cleanly skip HTTP/3 & use TCP/TLS 443
        if (blockQuicUdp443 && qType == 65) {
            strippedHttpsQueriesCount.incrementAndGet()
            val emptyResp = buildEmptyNoErrorResponse(dnsPayload) ?: return null
            return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, emptyResp)
        }

        // 2. Local DNS Sinkhole for Ads/Trackers, DoH Bypass, and Silent Auto-Updates
        if (domain != null) {
            val isAd = sinkholeEnabled && matchesDomainSet(domain, blockedAdDomains)
            val isUpdate = blockAppUpdates && matchesDomainSet(domain, blockedUpdateDomains)
            val isDoh = blockDohDot && matchesDomainSet(domain, blockedDohDomains)
            if (isAd || isUpdate || isDoh) {
                blockedRequestsCount.incrementAndGet()
                if (isUpdate) blockedUpdateQueriesCount.incrementAndGet()
                val sinkholeDns = buildSinkholeResponse(dnsPayload, qType) ?: return null
                return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, sinkholeDns)
            }
        }

        // 3. Return empty NOERROR for AAAA queries when IPv4-only TUN forwarding is active (prevents IPv6 bypass)
        if (stripAaaa && qType == 28) {
            val emptyResp = buildEmptyNoErrorResponse(dnsPayload) ?: return null
            return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, emptyResp)
        }

        if (!cacheEnabled && dnsCache.isNotEmpty()) {
            dnsCache.clear()
        }

        // 4. 30-Minute DNS Cache (cuts redundant DNS radio wakeups)
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

        // 5. Forward to upstream DNS via protected socket
        val upstreamResp = forwardDnsQuery(dnsPayload) ?: return null
        if (cacheEnabled && domain != null && upstreamResp.size >= 12) {
            val flagsHi = upstreamResp[2].toInt() and 0xFF
            val flagsLo = upstreamResp[3].toInt() and 0xFF
            val isTruncated = (flagsHi and 0x02) != 0
            val rcode = flagsLo and 0x0F
            if (rcode == 0 && !isTruncated) {
                dnsCache[cacheKey] = CachedDns(
                    responsePayload = upstreamResp.copyOf(),
                    expiresAtMillis = now + CACHE_TTL_MS
                )
                if (dnsCache.size > MAX_CACHE_ENTRIES) {
                    val expiredKeys = dnsCache.entries.filter { it.value.expiresAtMillis <= now }.map { it.key }
                    expiredKeys.forEach { dnsCache.remove(it) }
                    if (dnsCache.size > MAX_CACHE_ENTRIES) dnsCache.clear()
                }
            }
        }

        return wrapIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, upstreamResp)
    }

    private fun findQNameEnd(dns: ByteArray, startPos: Int = 12): Int {
        var pos = startPos
        var steps = 0
        while (pos < dns.size && steps < 128) {
            steps++
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            val typeBits = len and 0xC0
            if (typeBits == 0xC0) {
                return if (pos + 2 <= dns.size) pos + 2 else -1
            }
            if (typeBits != 0) return -1
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
        val questionEnd = qNameEnd + 4
        if (questionEnd > query.size) return null

        if (qType != 1 && qType != 28) {
            val out = ByteBuffer.allocate(questionEnd)
            out.put(query[0])
            out.put(query[1])
            out.putShort(0x8183.toShort())
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
        out.putShort(1)
        out.putInt(600) // TTL 600s so apps don't hammer blocked ad domains every few seconds
        out.putShort(rdataLen.toShort())
        out.put(ByteArray(rdataLen))
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
                socket.soTimeout = 2000
                val upstream = parseIpv4Address(upstreamDnsIp)
                val outPacket = DatagramPacket(query, query.size, upstream, 53)
                socket.send(outPacket)

                val buf = ByteArray(4096)
                val inPacket = DatagramPacket(buf, buf.size)
                socket.receive(inPacket)
                if (inPacket.length > 1372) {
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
        out.putShort(0x8380.toShort())
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
