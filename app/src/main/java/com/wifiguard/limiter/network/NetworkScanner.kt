package com.wifiguard.limiter.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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

    fun getCurrentWifiSignature(context: Context): String {
        val gwIp = getGatewayIp(context)
        if (gwIp.isBlank()) return ""
        val peers = readArpAndNeighTable()
        val gwMac = peers.firstOrNull { it.ip == gwIp }?.mac ?: ""
        return if (gwMac.isNotBlank()) "$gwIp|$gwMac" else gwIp
    }

    suspend fun probeIpForArp(ip: String) = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext
        runCatching {
            val addr = InetAddress.getByName(ip)
            addr.isReachable(350)
            // Also send a tiny UDP packet to trigger kernel ARP resolution even if ICMP is ignored
            java.net.DatagramSocket().use { ds ->
                val pkt = java.net.DatagramPacket(byteArrayOf(0), 1, addr, 5353)
                ds.send(pkt)
            }
        }
    }

    suspend fun scanActiveLanPeers(context: Context): List<LanPeer> = withContext(Dispatchers.IO) {
        val myIp = getLocalWifiIp(context)
        if (myIp.isBlank() || !myIp.contains(".")) return@withContext emptyList()
        val prefix = myIp.substringBeforeLast(".")

        // Fast parallel reachability sweep to populate kernel neighbor/ARP table
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

        // 1. Try `ip neigh` (works on Android 10+ where /proc/net/arp may be restricted)
        runCatching {
            val proc = ProcessBuilder("ip", "neigh").start()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    // Example: 192.168.1.15 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
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

        // 2. Fallback / supplement with /proc/net/arp
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
