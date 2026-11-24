package com.wifiguard.limiter.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import com.wifiguard.limiter.model.LimitPeriod
import java.util.Calendar

object AppUsageStatsHelper {

    fun hasUsageStatsPermission(context: Context): Boolean {
        return runCatching {
            val appOps = context.applicationContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    private fun periodStartMillis(period: LimitPeriod): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (period) {
            LimitPeriod.DAILY -> {}
            LimitPeriod.WEEKLY -> {
                val diff = (cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
                cal.add(Calendar.DAY_OF_YEAR, -diff)
            }
            LimitPeriod.MONTHLY -> cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Queries exact Wi-Fi usage in MB for each requested package using a single
     * NetworkStatsManager.querySummary() Binder IPC.
     */
    fun getWifiUsageMbByPackage(
        context: Context,
        packages: Collection<String>,
        period: LimitPeriod
    ): Map<String, Long> {
        if (packages.isEmpty() || !hasUsageStatsPermission(context)) return emptyMap()
        val appContext = context.applicationContext
        val nsm = appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyMap()
        val pm = appContext.packageManager
        val startMs = periodStartMillis(period)
        val endMs = System.currentTimeMillis()
        if (endMs <= startMs) return emptyMap()

        val uidToPkg = LinkedHashMap<Int, String>()
        for (pkg in packages) {
            runCatching {
                val uid = pm.getApplicationInfo(pkg, 0).uid
                uidToPkg.putIfAbsent(uid, pkg)
            }
        }
        if (uidToPkg.isEmpty()) return emptyMap()

        val bytesByUid = mutableMapOf<Int, Long>()
        runCatching {
            @Suppress("DEPRECATION")
            val stats = nsm.querySummary(
                ConnectivityManager.TYPE_WIFI,
                null,
                startMs,
                endMs
            ) ?: return emptyMap()
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val uid = bucket.uid
                    if (uid in uidToPkg) {
                        val delta = (bucket.rxBytes + bucket.txBytes).coerceAtLeast(0L)
                        bytesByUid[uid] = (bytesByUid[uid] ?: 0L) + delta
                    }
                }
            } finally {
                runCatching { stats.close() }
            }
        }

        val result = mutableMapOf<String, Long>()
        for ((uid, totalBytes) in bytesByUid) {
            val pkg = uidToPkg[uid] ?: continue
            val mb = totalBytes / (1024L * 1024L)
            if (mb > 0L) result[pkg] = mb
        }
        return result
    }

    /**
     * Returns total device Wi-Fi bytes in [startMs, endMs] from Android's kernel NetworkStatsManager.
     */
    fun getTotalWifiBytesInRange(context: Context, startMs: Long, endMs: Long): Long {
        if (endMs <= startMs || !hasUsageStatsPermission(context)) return -1L
        val nsm = context.applicationContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return -1L
        return runCatching {
            @Suppress("DEPRECATION")
            val bucket = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, startMs, endMs)
            (bucket.rxBytes + bucket.txBytes).coerceAtLeast(0L)
        }.getOrDefault(-1L)
    }

    /**
     * Returns total foreground app screen-on time in hours within [startMs, endMs] using UsageStatsManager.
     */
    fun getActiveForegroundHoursInRange(context: Context, startMs: Long, endMs: Long): Double {
        if (endMs <= startMs || !hasUsageStatsPermission(context)) return 0.0
        val usm = context.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0.0
        return runCatching {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs) ?: return 0.0
            var totalMs = 0L
            for (s in stats) {
                val fg = s.totalTimeInForeground
                if (fg > 0L) totalMs += fg
            }
            (totalMs.toDouble() / 3_600_000.0).coerceAtLeast(0.0)
        }.getOrDefault(0.0)
    }

    /**
     * Computes a real kernel-backed historical baseline (MB per active foreground hour)
     * over the 7-day window immediately preceding [beforeMillis] (e.g., before Saver was enabled).
     * Returns -1.0 if insufficient history (< 1.0 active hour or permission missing).
     */
    fun computeBaselineMbPerActiveHour(context: Context, beforeMillis: Long, lookbackDays: Int = 7): Double {
        if (!hasUsageStatsPermission(context)) return -1.0
        val endMs = beforeMillis
        val startMs = endMs - (lookbackDays.coerceIn(1, 30) * 24L * 3_600_000L)
        val totalBytes = getTotalWifiBytesInRange(context, startMs, endMs)
        if (totalBytes <= 0L) return -1.0
        val activeHours = getActiveForegroundHoursInRange(context, startMs, endMs)
        if (activeHours < 1.0) return -1.0
        val totalMb = totalBytes.toDouble() / (1024.0 * 1024.0)
        return (totalMb / activeHours).coerceIn(10.0, 2048.0)
    }

    /**
     * Returns today's active foreground hours since local midnight.
     */
    fun getTodayActiveForegroundHours(context: Context): Double {
        val startMs = periodStartMillis(LimitPeriod.DAILY)
        val endMs = System.currentTimeMillis()
        return getActiveForegroundHoursInRange(context, startMs, endMs)
    }
}
