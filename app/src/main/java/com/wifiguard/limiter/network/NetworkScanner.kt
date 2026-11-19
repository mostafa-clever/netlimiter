package com.wifiguard.limiter.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

data class LanPeer(
    val ip: String,
    val mac: String
)

object NetworkScanner {

    fun getLocalWifiIp(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return ""
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return ""
        val ipv4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        return ipv4?.address?.hostAddress ?: ""
    }

    fun getGatewayIp(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return ""
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return ""
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
        return gw?.hostAddress ?: ""
    }

    private fun getWifiSsidAndBssid(context: Context): Pair<String, String> {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps?.transportInfo is WifiInfo) {
                caps.transportInfo as WifiInfo
            } else {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wm.connectionInfo
            }
            val rawSsid = info?.ssid?.trim('"') ?: ""
            val ssid = if (rawSsid.equals("<unknown ssid>", ignoreCase = true)) "" else rawSsid
            val rawBssid = info?.bssid?.lowercase() ?: ""
            val bssid = if (rawBssid == "02:00:00:00:00:00" || rawBssid == "00:00:00:00:00:00") "" else rawBssid
            ssid to bssid
        }.getOrDefault("" to "")
    }

    /**
     * Returns a unique hardware fingerprint for the current Wi-Fi network:
     * format: "ssid=<SSID>|bssid=<AP_MAC>|gwmac=<ROUTER_MAC>"
     * Never relies on common router IP (192.168.1.1) alone.
     */
    fun getCurrentWifiSignature(context: Context): String {
        val (ssid, bssid) = getWifiSsidAndBssid(context)
        val gwIp = getGatewayIp(context)
        var gwMac = ""
        if (gwIp.isNotBlank()) {
            val peers = readArpAndNeighTable()
            gwMac = peers.firstOrNull { it.ip == gwIp }?.mac ?: ""
        }
        val parts = mutableListOf<String>()
        if (ssid.isNotBlank()) parts.add("ssid=$ssid")
        if (bssid.isNotBlank()) parts.add("bssid=$bssid")
        if (gwMac.isNotBlank()) parts.add("gwmac=$gwMac")
        return parts.joinToString("|")
    }

    suspend fun getCurrentWifiSignatureActive(context: Context): String = withContext(Dispatchers.IO) {
        val gwIp = getGatewayIp(context)
        if (gwIp.isNotBlank()) {
            probeIpForArp(gwIp)
        }
        getCurrentWifiSignature(context)
    }

    fun formatSignatureForDisplay(sig: String): String {
        if (sig.isBlank()) return "غير محددة"
        val map = parseSig(sig)
        val ssid = map["ssid"] ?: ""
        val mac = map["bssid"] ?: map["gwmac"] ?: ""
        return when {
            ssid.isNotBlank() && mac.isNotBlank() -> "$ssid ($mac)"
            ssid.isNotBlank() -> ssid
            mac.isNotBlank() -> "راوتر ($mac)"
            else -> sig
        }
    }

    fun matchesNetworkSignature(current: String, target: String): Boolean {
        if (current.isBlank() || target.isBlank()) return false
        val cur = parseSig(current)
        val tgt = parseSig(target)

        // 1. Exact Router BSSID match (strongest, globally unique)
        val curBssid = cur["bssid"]
        val tgtBssid = tgt["bssid"]
        if (!curBssid.isNullOrBlank() && !tgtBssid.isNullOrBlank()) {
            return curBssid.equals(tgtBssid, ignoreCase = true)
        }

        // 2. Exact Router Gateway MAC match (globally unique)
        val curGwMac = cur["gwmac"]
        val tgtGwMac = tgt["gwmac"]
        if (!curGwMac.isNullOrBlank() && !tgtGwMac.isNullOrBlank()) {
            return curGwMac.equals(tgtGwMac, ignoreCase = true)
        }

        // 3. Fallback to SSID match if hardware MAC unavailable
        val curSsid = cur["ssid"]
        val tgtSsid = tgt["ssid"]
        if (!curSsid.isNullOrBlank() && !tgtSsid.isNullOrBlank()) {
            return curSsid == tgtSsid
        }

        return false
    }

    private fun parseSig(sig: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        sig.split("|").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                map[part.substring(0, idx)] = part.substring(idx + 1)
            }
        }
        return map
    }

    /**
     * Reliably detects if a host IP is physically alive on the Wi-Fi LAN on Android 11-15 (even when /proc/net/arp is restricted).
     * Both a successful ICMP echo OR a TCP Connection Refused (ECONNREFUSED / TCP RST) prove the device kernel is active on Wi-Fi.
     */
    suspend fun isHostAliveOnLan(ip: String): Boolean = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext false
        val icmpOk = runCatching { InetAddress.getByName(ip).isReachable(450) }.getOrDefault(false)
        if (icmpOk) return@withContext true

        // TCP connect probe: Connection Refused (ConnectException) means the phone actively sent TCP RST -> definitely on Wi-Fi!
        for (port in listOf(LocalLanClient.CLIENT_PORT, 5353, 80)) {
            val alive = try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(ip, port), 400)
                }
                true
            } catch (e: java.net.ConnectException) {
                true // TCP RST received -> host is physically online on Wi-Fi!
            } catch (_: Throwable) {
                false
            }
            if (alive) return@withContext true
        }
        false
    }

    suspend fun probeIpForArp(ip: String) = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext
        runCatching {
            val addr = InetAddress.getByName(ip)
            addr.isReachable(350)
            DatagramSocket().use { ds ->
                val pkt = DatagramPacket(byteArrayOf(0), 1, addr, 5353)
                ds.send(pkt)
            }
        }
    }

    suspend fun scanActiveLanPeers(context: Context): List<LanPeer> = withContext(Dispatchers.IO) {
        val myIp = getLocalWifiIp(context)
        if (myIp.isBlank() || !myIp.contains(".")) return@withContext emptyList()
        val prefix = myIp.substringBeforeLast(".")

        coroutineScope {
            (1..254).chunked(32).forEach { chunk ->
                chunk.map { lastOctet ->
                    async {
                        val target = "$prefix.$lastOctet"
                        if (target != myIp) {
                            runCatching { InetAddress.getByName(target).isReachable(180) }
                        }
                    }
                }.awaitAll()
            }
        }

        readArpAndNeighTable()
    }

    fun readArpAndNeighTable(): List<LanPeer> {
        val map = linkedMapOf<String, String>()

        runCatching {
            val proc = ProcessBuilder("ip", "neigh").start()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 5) {
                        val ip = parts[0]
                        val llIdx = parts.indexOf("lladdr")
                        if (llIdx != -1 && llIdx + 1 < parts.size) {
                            val mac = parts[llIdx + 1].lowercase()
                            val state = parts.last().uppercase()
                            if (mac != "00:00:00:00:00:00" && state != "FAILED") {
                                map[ip] = mac
                            }
                        }
                    }
                }
            }
        }

        runCatching {
            val file = File("/proc/net/arp")
            if (file.exists() && file.canRead()) {
                file.readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3].lowercase()
                        if (mac.contains(":") && mac != "00:00:00:00:00:00") {
                            map.putIfAbsent(ip, mac)
                        }
                    }
                }
            }
        }

        return map.entries.map { LanPeer(ip = it.key, mac = it.value) }
    }
}
