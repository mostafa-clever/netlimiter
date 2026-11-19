package com.wifiguard.limiter.vpn

import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

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
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val mediaTokenBucket = TokenBucket()

    @Volatile var mediaCdnSaverActive: Boolean = false
    @Volatile var blockAppUpdatesActive: Boolean = false
    @Volatile private var running = true

    fun updateMediaSaverConfig(enabled: Boolean, mediaSpeedMbps: Double, blockUpdates: Boolean) {
        mediaCdnSaverActive = enabled
        blockAppUpdatesActive = blockUpdates
        if (enabled && mediaSpeedMbps > 0.0) {
            mediaTokenBucket.setRateMbps(mediaSpeedMbps)
        } else {
            mediaTokenBucket.setRateBytesPerSec(Long.MAX_VALUE)
        }
    }

    fun start() {
        executor.execute {
            runCatching {
                serverSocket = ServerSocket(PROXY_PORT, 64, InetAddress.getByName("127.0.0.1"))
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { executor.shutdownNow() }
    }

    private fun isMediaCdnHost(host: String): Boolean {
        val h = host.lowercase()
        return MEDIA_CDN_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    private fun isAppUpdateHost(host: String): Boolean {
        val h = host.lowercase()
        return AUTO_UPDATE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun handleClient(client: Socket) {
        client.use { c ->
            runCatching {
                c.tcpNoDelay = true
                c.soTimeout = 30_000
                val cIn = c.getInputStream()
                val cOut = c.getOutputStream()

                val requestHeader = readHttpHeader(cIn) ?: return
                val lines = requestHeader.split("\r\n")
                val firstLine = lines.firstOrNull() ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val target = parts[1]

                if (method == "CONNECT") {
                    val hostPort = target.split(":")
                    val host = hostPort[0]
                    val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                    if (blockAppUpdatesActive && isAppUpdateHost(host)) {
                        cOut.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        cOut.flush()
                        return
                    }

                    val useMediaBucket = mediaCdnSaverActive && isMediaCdnHost(host)

                    val remote = Socket()
                    vpnService.protect(remote)
                    remote.tcpNoDelay = true
                    remote.soTimeout = 30_000
                    remote.connect(InetSocketAddress(host, port), 8000)

                    remote.use { r ->
                        cOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        cOut.flush()

                        val upFuture = executor.submit {
                            copyStream(cIn, r.getOutputStream(), paced = false, useMediaBucket = false)
                        }
                        copyStream(r.getInputStream(), cOut, paced = true, useMediaBucket = useMediaBucket)
                        upFuture.cancel(true)
                    }
                } else {
                    val uri = URI(target)
                    val host = uri.host ?: extractHostHeader(lines) ?: return
                    val port = if (uri.port > 0) uri.port else 80

                    if (blockAppUpdatesActive && isAppUpdateHost(host)) {
                        cOut.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        cOut.flush()
                        return
                    }

                    val useMediaBucket = mediaCdnSaverActive && isMediaCdnHost(host)
                    val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
                        (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")

                    val remote = Socket()
                    vpnService.protect(remote)
                    remote.tcpNoDelay = true
                    remote.soTimeout = 30_000
                    remote.connect(InetSocketAddress(host, port), 8000)

                    remote.use { r ->
                        val rOut = r.getOutputStream()
                        val rebuilt = StringBuilder()
                        rebuilt.append("$method $path ${parts.getOrElse(2) { "HTTP/1.1" }}\r\n")
                        for (i in 1 until lines.size) {
                            val l = lines[i]
                            if (l.isNotEmpty() && !l.lowercase().startsWith("proxy-connection:")) {
                                rebuilt.append(l).append("\r\n")
                            }
                        }
                        rebuilt.append("\r\n")
                        rOut.write(rebuilt.toString().toByteArray(Charsets.US_ASCII))
                        rOut.flush()

                        val upFuture = executor.submit {
                            copyStream(cIn, rOut, paced = false, useMediaBucket = false)
                        }
                        copyStream(r.getInputStream(), cOut, paced = true, useMediaBucket = useMediaBucket)
                        upFuture.cancel(true)
                    }
                }
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, paced: Boolean, useMediaBucket: Boolean) {
        val buf = ByteArray(16384)
        while (running) {
            var maxRead = buf.size
            val bucket = if (useMediaBucket && !mediaTokenBucket.isUnlimited()) mediaTokenBucket else tokenBucket
            if (paced && !bucket.isUnlimited()) {
                var avail = bucket.availableBytes()
                while (avail < 256 && running) {
                    Thread.sleep(bucket.millisUntilBytes(256))
                    avail = bucket.availableBytes()
                }
                if (!running) break
                maxRead = avail.coerceIn(256, buf.size)
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
        var prev = 0
        var cur: Int
        var endState = 0
        while (baos.size() < 16384) {
            cur = input.read()
            if (cur == -1) return null
            baos.write(cur)
            if (cur == '\n'.code && prev == '\r'.code) {
                endState++
                if (endState == 2) break
            } else if (cur != '\r'.code) {
                endState = 0
            }
            prev = cur
        }
        return baos.toString("US-ASCII").trimEnd()
    }

    private fun extractHostHeader(lines: List<String>): String? {
        val h = lines.firstOrNull { it.lowercase().startsWith("host:") } ?: return null
        return h.substringAfter(":").trim().substringBefore(":")
    }
}
