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
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
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
            LimitPeriod.WEEKLY -> cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            LimitPeriod.MONTHLY -> cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Queries exact Wi-Fi usage in MB for each package using Android's NetworkStatsManager
     * (works on Android 8–15 where TrafficStats.getUidRxBytes returns -1 for other apps).
     */
    fun getWifiUsageMbByPackage(
        context: Context,
        packages: Collection<String>,
        period: LimitPeriod
    ): Map<String, Long> {
        if (packages.isEmpty() || !hasUsageStatsPermission(context)) return emptyMap()
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyMap()
        val pm = context.packageManager
        val startMs = periodStartMillis(period)
        val endMs = System.currentTimeMillis()
        val result = mutableMapOf<String, Long>()
        val bucket = NetworkStats.Bucket()

        for (pkg in packages) {
            runCatching {
                val uid = pm.getApplicationInfo(pkg, 0).uid
                @Suppress("DEPRECATION")
                val stats = nsm.queryDetailsForUid(
                    ConnectivityManager.TYPE_WIFI,
                    null,
                    startMs,
                    endMs,
                    uid
                )
                var totalBytes = 0L
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    totalBytes += bucket.rxBytes + bucket.txBytes
                }
                stats.close()
                val mb = totalBytes / (1024L * 1024L)
                if (mb > 0L) result[pkg] = mb
            }
        }
        return result
    }
}
