package com.wifiguard.limiter.vpn

import android.net.VpnService
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class LocalHttpProxyServer(
    private val vpnService: VpnService,
    private val tokenBucket: TokenBucket
) {
    companion object {
        const val PROXY_PORT = 8888

        private val MEDIA_CDN_SUFFIXES = listOf(
            "googlevideo.com",
            "tiktokcdn.com",
            "tiktokcdn-us.com",
            "tiktokv.com",
            "fbcdn.net",
            "cdninstagram.com",
            "twimg.com",
            "vimeocdn.com",
            "nflxvideo.net",
            "sc-cdn.net"
        )

        private val AUTO_UPDATE_HOSTS = listOf(
            "gvt1.com",
            "ota.googlezip.net",
            "android.clients.google.com"
        )

        private val DOH_HOSTS = listOf(
            "dns.google",
            "dns.google.com",
            "cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.nextdns.io"
        )
    }

    private var serverSocket: ServerSocket? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = ThreadPoolExecutor(
        8,
        160,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(256)
    ).apply { allowCoreThreadTimeOut(true) }

    private val mediaTokenBucket = TokenBucket()

    @Volatile var boundPort: Int = PROXY_PORT
        private set
    @Volatile var mediaCdnSaverActive: Boolean = false
    @Volatile var blockAppUpdatesActive: Boolean = false
    @Volatile var blockDohActive: Boolean = false
    @Volatile private var running = true

    fun updateMediaSaverConfig(enabled: Boolean, mediaSpeedMbps: Double, blockUpdates: Boolean, blockDoh: Boolean = false) {
        mediaCdnSaverActive = enabled
        blockAppUpdatesActive = blockUpdates
        blockDohActive = blockDoh
        if (enabled && mediaSpeedMbps > 0.0) {
            mediaTokenBucket.setRateMbps(mediaSpeedMbps)
        } else {
            mediaTokenBucket.setUnlimited()
        }
    }

    fun start() {
        val bound = bindServerSocket() ?: return
        serverSocket = bound
        boundPort = bound.localPort
        runCatching {
            executor.execute {
                while (running && !bound.isClosed) {
                    val client = runCatching { bound.accept() }.getOrNull() ?: break
                    val submitted = runCatching {
                        executor.execute { handleClient(client) }
                    }.isSuccess
                    if (!submitted) {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    private fun bindServerSocket(): ServerSocket? {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        // Try default port 8888 first with reuseAddress, then fall back to an ephemeral port
        for (port in listOf(PROXY_PORT, 0)) {
            val s = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(loopback, port), 64)
                }
            }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    fun closeActiveConnections() {
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        closeActiveConnections()
        runCatching { executor.shutdownNow() }
    }

    private fun normalizeHost(host: String): String =
        host.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase()

    private fun isMediaCdnHost(host: String): Boolean {
        val h = normalizeHost(host)
        return MEDIA_CDN_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    private fun isAppUpdateHost(host: String): Boolean {
        val h = normalizeHost(host)
        return AUTO_UPDATE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun isDohHost(host: String): Boolean {
        val h = normalizeHost(host)
        return DOH_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = authority.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx <= 1) return null
            val host = trimmed.substring(1, closeIdx)
            val rest = trimmed.substring(closeIdx + 1)
            val port = if (rest.startsWith(":")) {
                rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else defaultPort
            host to port
        } else {
            val colonIdx = trimmed.lastIndexOf(':')
            if (colonIdx > 0) {
                val host = trimmed.substring(0, colonIdx)
                val port = trimmed.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                host to port
            } else {
                trimmed to defaultPort
            }
        }
    }

    private fun handleClient(client: Socket) {
        activeSockets.add(client)
        try {
            client.use { c ->
                runCatching {
                    c.tcpNoDelay = true
                    c.soTimeout = 30_000
                    val cIn = BufferedInputStream(c.getInputStream(), 8192)
                    val cOut = c.getOutputStream()

                    val requestHeader = readHttpHeader(cIn) ?: return
                    val lines = requestHeader.split("\r\n")
                    val firstLine = lines.firstOrNull() ?: return
                    val parts = firstLine.split(" ")
                    if (parts.size < 2) return

                    val method = parts[0].uppercase()
                    val target = parts[1]

                    if (method == "CONNECT") {
                        val (host, port) = parseHostPort(target, 443) ?: return
                        if ((blockAppUpdatesActive && isAppUpdateHost(host)) || (blockDohActive && isDohHost(host))) {
                            cOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            cOut.flush()
                            return
                        }

                        val useMediaBucket = mediaCdnSaverActive && isMediaCdnHost(host)
                        val remote = Socket()
                        activeSockets.add(remote)
                        try {
                            remote.use { r ->
                                if (!vpnService.protect(r)) return
                                r.tcpNoDelay = true
                                r.soTimeout = 300_000
                                c.soTimeout = 300_000
                                r.connect(InetSocketAddress(host, port), 8000)

                                cOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                cOut.flush()

                                val upFuture = runCatching {
                                    executor.submit {
                                        copyStream(cIn, r.getOutputStream(), paced = true, useMediaBucket = false)
                                    }
                                }.getOrNull() ?: return
                                try {
                                    copyStream(r.getInputStream(), cOut, paced = true, useMediaBucket = useMediaBucket)
                                } finally {
                                    upFuture.cancel(true)
                                }
                            }
                        } finally {
                            activeSockets.remove(remote)
                        }
                    } else {
                        val parsed = parsePlainHttpTarget(target, lines) ?: return
                        val (host, port, path) = parsed

                        if ((blockAppUpdatesActive && isAppUpdateHost(host)) || (blockDohActive && isDohHost(host))) {
                            cOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            cOut.flush()
                            return
                        }

                        val useMediaBucket = mediaCdnSaverActive && isMediaCdnHost(host)
                        val remote = Socket()
                        activeSockets.add(remote)
                        try {
                            remote.use { r ->
                                if (!vpnService.protect(r)) return
                                r.tcpNoDelay = true
                                r.soTimeout = 30_000
                                r.connect(InetSocketAddress(host, port), 8000)

                                val rOut = r.getOutputStream()
                                val rebuilt = StringBuilder()
                                rebuilt.append("$method $path ${parts.getOrElse(2) { "HTTP/1.1" }}\r\n")
                                val isUpgrade = lines.any { it.lowercase().startsWith("upgrade:") }
                                for (i in 1 until lines.size) {
                                    val l = lines[i]
                                    val lower = l.lowercase()
                                    if (l.isNotEmpty() &&
                                        !lower.startsWith("proxy-connection:") &&
                                        (isUpgrade || !lower.startsWith("connection:"))
                                    ) {
                                        rebuilt.append(l).append("\r\n")
                                    }
                                }
                                if (!isUpgrade) rebuilt.append("Connection: close\r\n")
                                rebuilt.append("\r\n")
                                if (isUpgrade) {
                                    r.soTimeout = 300_000
                                    c.soTimeout = 300_000
                                }
                                rOut.write(rebuilt.toString().toByteArray(Charsets.US_ASCII))
                                rOut.flush()

                                val upFuture = runCatching {
                                    executor.submit {
                                        copyStream(cIn, rOut, paced = true, useMediaBucket = false)
                                    }
                                }.getOrNull() ?: return
                                try {
                                    copyStream(r.getInputStream(), cOut, paced = true, useMediaBucket = useMediaBucket)
                                } finally {
                                    upFuture.cancel(true)
                                }
                            }
                        } finally {
                            activeSockets.remove(remote)
                        }
                    }
                }
            }
        } finally {
            activeSockets.remove(client)
        }
    }

    private fun parsePlainHttpTarget(target: String, lines: List<String>): Triple<String, Int, String>? {
        return if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            val isHttps = target.startsWith("https://", ignoreCase = true)
            val withoutScheme = target.substringAfter("://")
            val slashIdx = withoutScheme.indexOf('/')
            val authority = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
            val path = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
            val (host, port) = parseHostPort(authority, if (isHttps) 443 else 80) ?: return null
            Triple(host, port, path.ifEmpty { "/" })
        } else {
            val hostLine = lines.firstOrNull { it.lowercase().startsWith("host:") } ?: return null
            val authority = hostLine.substringAfter(":").trim()
            val (host, port) = parseHostPort(authority, 80) ?: return null
            Triple(host, port, if (target.startsWith("/")) target else "/$target")
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, paced: Boolean, useMediaBucket: Boolean) {
        val buf = ByteArray(16384)
        while (running) {
            if (tokenBucket.isBlocked()) break
            var maxRead = buf.size
            if (paced) {
                val globalLimited = !tokenBucket.isUnlimited()
                val mediaLimited = useMediaBucket && !mediaTokenBucket.isUnlimited()
                if (globalLimited || mediaLimited) {
                    while (running) {
                        if (tokenBucket.isBlocked()) return
                        val gAvail = if (globalLimited) tokenBucket.availableBytes() else buf.size
                        val mAvail = if (mediaLimited) mediaTokenBucket.availableBytes() else buf.size
                        val avail = minOf(gAvail, mAvail)
                        if (avail >= 256) {
                            maxRead = avail.coerceIn(256, buf.size)
                            break
                        }
                        val gSleep = if (globalLimited && gAvail < 256) tokenBucket.millisUntilBytes(256) else 0L
                        val mSleep = if (mediaLimited && mAvail < 256) mediaTokenBucket.millisUntilBytes(256) else 0L
                        val sleepMs = maxOf(gSleep, mSleep, 5L)
                        try {
                            Thread.sleep(sleepMs)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    if (!running) break
                }
            }
            val read = runCatching { input.read(buf, 0, maxRead) }.getOrElse { -1 }
            if (read <= 0) break
            if (paced) {
                tokenBucket.consume(read)
                if (useMediaBucket) mediaTokenBucket.consume(read)
            }
            runCatching {
                output.write(buf, 0, read)
                output.flush()
            }.onFailure { return }
        }
    }

    private fun readHttpHeader(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var state = 0 // Strict \r\n\r\n detector: 0 -> \r(1) -> \n(2) -> \r(3) -> \n(4)
        while (baos.size() < 16384) {
            val cur = input.read()
            if (cur == -1) return null
            baos.write(cur)
            state = when {
                state == 0 && cur == '\r'.code -> 1
                state == 1 && cur == '\n'.code -> 2
                state == 2 && cur == '\r'.code -> 3
                state == 3 && cur == '\n'.code -> 4
                cur == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) {
                return baos.toString("US-ASCII").trimEnd()
            }
        }
        // Header exceeded 16KB without terminating \r\n\r\n — reject to prevent smuggling
        return null
    }
}
