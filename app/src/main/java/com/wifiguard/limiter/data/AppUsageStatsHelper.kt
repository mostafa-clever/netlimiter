package com.wifiguard.limiter.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
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
                // Always move backward to the start of the current locale week (avoids forward jump on Saturday-start locales)
                val diff = (cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
                cal.add(Calendar.DAY_OF_YEAR, -diff)
            }
            LimitPeriod.MONTHLY -> cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Queries exact Wi-Fi usage in MB for each requested package using a single
     * NetworkStatsManager.querySummary() Binder IPC (avoiding N+1 IPCs and shared-UID double counting).
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

        // Map each unique UID to its primary requested package (prevents double-counting sharedUserId packages)
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
}
