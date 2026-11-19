package com.wifiguard.limiter.vpn

import java.util.concurrent.atomic.AtomicLong

class TokenBucket {
    private val rateBytesPerSec = AtomicLong(Long.MAX_VALUE)
    private var tokens: Double = 65536.0
    private var lastRefillNanos: Long = System.nanoTime()

    fun setRateMbps(mbps: Double) {
        if (mbps <= 0.0) {
            rateBytesPerSec.set(Long.MAX_VALUE)
        } else {
            // 1 Mbps = 1,000,000 bits/sec = 125,000 bytes/sec
            val bytesPerSec = (mbps * 125_000.0).toLong().coerceAtLeast(8_000L)
            rateBytesPerSec.set(bytesPerSec)
        }
    }

    fun setRateBytesPerSec(bytesPerSec: Long) {
        rateBytesPerSec.set(bytesPerSec)
    }

    fun isUnlimited(): Boolean = rateBytesPerSec.get() == Long.MAX_VALUE

    @Synchronized
    fun availableBytes(): Int {
        val rate = rateBytesPerSec.get()
        if (rate == Long.MAX_VALUE) return 65536
        if (rate <= 0L) return 0

        val now = System.nanoTime()
        val elapsedSec = ((now - lastRefillNanos).coerceAtLeast(0L)) / 1_000_000_000.0
        lastRefillNanos = now

        // Allow up to 250ms burst (min 16KB) so small UI/JSON requests feel snappy
        val maxBurst = maxOf(16_384.0, rate * 0.25)
        tokens = minOf(maxBurst, tokens + elapsedSec * rate.toDouble())
        return tokens.toInt().coerceAtLeast(0)
    }

    @Synchronized
    fun millisUntilBytes(minBytes: Int): Long {
        val rate = rateBytesPerSec.get()
        if (rate == Long.MAX_VALUE) return 0L
        if (rate <= 0L) return 50L
        val needed = (minBytes - tokens).coerceAtLeast(0.0)
        return ((needed * 1000.0) / rate.toDouble()).toLong().coerceIn(5L, 50L)
    }

    @Synchronized
    fun consume(bytes: Int) {
        val rate = rateBytesPerSec.get()
        if (rate == Long.MAX_VALUE) return
        tokens = (tokens - bytes.toDouble()).coerceAtLeast(0.0)
    }
}
