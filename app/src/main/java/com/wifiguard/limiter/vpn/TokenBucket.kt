package com.wifiguard.limiter.vpn

import java.util.concurrent.atomic.AtomicLong

class TokenBucket {
    private val rateBytesPerSec = AtomicLong(Long.MAX_VALUE)
    private var tokens: Double = 65536.0
    private var lastRefillNanos: Long = System.nanoTime()

    @Synchronized
    fun setRateMbps(mbps: Double) {
        if (!mbps.isFinite() || mbps <= 0.0) {
            setUnlimited()
        } else {
            // 1 Mbps = 1,000,000 bits/sec = 125,000 bytes/sec
            val bytesPerSec = (mbps * 125_000.0).toLong().coerceAtLeast(8_000L)
            setRateBytesPerSec(bytesPerSec)
        }
    }

    @Synchronized
    fun setUnlimited() {
        rateBytesPerSec.set(Long.MAX_VALUE)
        tokens = 65536.0
        lastRefillNanos = System.nanoTime()
    }

    @Synchronized
    fun setRateBytesPerSec(bytesPerSec: Long) {
        val prev = rateBytesPerSec.getAndSet(bytesPerSec)
        if (prev != bytesPerSec) {
            lastRefillNanos = System.nanoTime()
            if (bytesPerSec <= 0L) {
                tokens = 0.0
            } else if (bytesPerSec < Long.MAX_VALUE) {
                val maxBurst = maxOf(16_384.0, bytesPerSec * 0.25)
                tokens = minOf(tokens.coerceAtLeast(0.0), maxBurst)
            }
        }
    }

    fun isUnlimited(): Boolean = rateBytesPerSec.get() == Long.MAX_VALUE

    fun isBlocked(): Boolean = rateBytesPerSec.get() <= 0L

    private fun refillLocked(rate: Long) {
        val now = System.nanoTime()
        val elapsedSec = ((now - lastRefillNanos).coerceAtLeast(0L)) / 1_000_000_000.0
        lastRefillNanos = now
        val maxBurst = maxOf(16_384.0, rate * 0.25)
        tokens = minOf(maxBurst, tokens + elapsedSec * rate.toDouble())
    }

    @Synchronized
    fun availableBytes(): Int {
        val rate = rateBytesPerSec.get()
        if (rate == Long.MAX_VALUE) return 65536
        if (rate <= 0L) return 0
        refillLocked(rate)
        return tokens.toInt().coerceAtLeast(0)
    }

    @Synchronized
    fun millisUntilBytes(minBytes: Int): Long {
        val rate = rateBytesPerSec.get()
        if (rate == Long.MAX_VALUE) return 0L
        if (rate <= 0L) return 100L
        refillLocked(rate)
        val needed = (minBytes - tokens).coerceAtLeast(0.0)
        if (needed <= 0.0) return 0L
        return ((needed * 1000.0) / rate.toDouble()).toLong().coerceIn(5L, 100L)
    }

    @Synchronized
    fun consume(bytes: Int) {
        val rate = rateBytesPerSec.get()
        if (rate == Long.MAX_VALUE || bytes <= 0) return
        val maxBurst = maxOf(16_384.0, rate * 0.25)
        // Allow bounded token debt so concurrent threads cannot bypass the rate limit
        tokens = (tokens - bytes.toDouble()).coerceAtLeast(-maxBurst)
    }
}
