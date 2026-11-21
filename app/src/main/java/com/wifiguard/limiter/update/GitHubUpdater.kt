package com.wifiguard.limiter.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.wifiguard.limiter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseUpdateInfo(
    val versionTag: String,
    val remoteBuildNumber: Long,
    val localBuildNumber: Long,
    val hasUpdate: Boolean,
    val publishedAt: String,
    val downloadUrl: String,
    val notes: String
)

object GitHubUpdater {

    const val DEFAULT_REPO = "mostafa-clever/netlimiter"
    const val DIRECT_APK_URL = "https://github.com/mostafa-clever/netlimiter/releases/download/latest/WifiGuard.apk"

    private val REPO_REGEX = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private val downloadMutex = Mutex()

    fun getInstalledVersionCode(context: Context): Long {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(1L)
    }

    private fun getInstalledVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    private fun isRemoteSemVerNewer(remoteTag: String, localVersionName: String): Boolean {
        val remoteParts = remoteTag.trim().removePrefix("v").removePrefix("V")
            .split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val localParts = localVersionName.trim().removePrefix("v").removePrefix("V")
            .split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        if (remoteParts.isEmpty() || localParts.isEmpty()) return false
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    suspend fun checkLatestRelease(
        context: Context,
        repoPath: String = DEFAULT_REPO
    ): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanRepo = repoPath.trim().ifBlank { DEFAULT_REPO }
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .trim('/')
            if (!REPO_REGEX.matches(cleanRepo)) {
                throw IllegalArgumentException("أدخل اسم المستودع بصيغة صحيحة: username/repo")
            }

            val localVersion = getInstalledVersionCode(context)
            val localVersionName = getInstalledVersionName(context)

            val firstResult = fetchReleaseJson("https://api.github.com/repos/$cleanRepo/releases/tags/latest")
            val obj = when {
                firstResult.rateLimited -> throw IllegalStateException("تم تجاوز حد طلبات GitHub مؤقتاً. حاول لاحقاً.")
                firstResult.json != null -> firstResult.json
                else -> {
                    val second = fetchReleaseJson("https://api.github.com/repos/$cleanRepo/releases/latest")
                    when {
                        second.rateLimited -> throw IllegalStateException("تم تجاوز حد طلبات GitHub مؤقتاً. حاول لاحقاً.")
                        second.json != null -> second.json
                        else -> throw IllegalStateException("تعذر الوصول لإصدارات GitHub.")
                    }
                }
            }

            val tag = obj.optString("tag_name", "latest")
            val publishedAt = obj.optString("published_at", "")
            val notes = obj.optString("body", "")

            val buildMatch = Regex("build:(\\d+)").find(notes)
            val remoteBuild = buildMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

            val assets = obj.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) {
                apkUrl = "https://github.com/$cleanRepo/releases/download/latest/WifiGuard.apk"
            }

            val hasUpdate = when {
                remoteBuild > 0L -> remoteBuild > localVersion
                else -> isRemoteSemVerNewer(tag, localVersionName)
            }

            ReleaseUpdateInfo(
                versionTag = if (remoteBuild > 0L) "1.0.$remoteBuild" else tag,
                remoteBuildNumber = remoteBuild,
                localBuildNumber = localVersion,
                hasUpdate = hasUpdate,
                publishedAt = publishedAt,
                downloadUrl = apkUrl,
                notes = notes
            )
        }
    }

    private data class FetchResult(val json: JSONObject?, val rateLimited: Boolean)

    private fun fetchReleaseJson(urlStr: String): FetchResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WifiGuard-Android")
            }
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                return FetchResult(null, rateLimited = true)
            }
            if (code !in 200..299) {
                return FetchResult(null, rateLimited = false)
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            FetchResult(JSONObject(text), rateLimited = false)
        } catch (_: Exception) {
            FetchResult(null, rateLimited = false)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun openHttpsFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl.trim()
        repeat(6) {
            if (!currentUrl.startsWith("https://", ignoreCase = true)) {
                throw SecurityException("رابط التحديث غير آمن (يجب أن يبدأ بـ https://)")
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "WifiGuard-Android")
            }
            val code = conn.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("إعادة توجيه غير صالحة من الخادم")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("فشل تحميل التحديث (رمز الخادم: $code)")
            }
        }
        throw IllegalStateException("تجاوز عدد إعادة التوجيه أثناء تحميل التحديث")
    }

    suspend fun downloadAndInstallApk(context: Context, downloadUrl: String = DIRECT_APK_URL): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.ENABLE_GITHUB_UPDATER) {
            return@withContext Result.failure(UnsupportedOperationException("التحديث المباشر غير مفعل في هذه النسخة."))
        }
        if (!downloadMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("جاري تحميل التحديث بالفعل..."))
        }
        val appContext = context.applicationContext
        try {
            runCatching {
                val updatesDir = File(appContext.cacheDir, "apk_updates").apply { mkdirs() }
                val tmpFile = File(updatesDir, "WifiGuard-update.apk.tmp")
                val apkFile = File(updatesDir, "WifiGuard-update.apk")
                if (tmpFile.exists()) tmpFile.delete()

                val conn = openHttpsFollowingRedirects(downloadUrl)
                var bytesWritten = 0L
                val expectedLength = conn.contentLengthLong
                try {
                    conn.inputStream.use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                bytesWritten += read
                            }
                            output.flush()
                        }
                    }
                } finally {
                    runCatching { conn.disconnect() }
                }

                if (bytesWritten <= 4L || (expectedLength > 0L && bytesWritten != expectedLength)) {
                    tmpFile.delete()
                    throw IllegalStateException("ملف التحديث غير مكتمل. تأكد من الاتصال وحاول مجدداً.")
                }

                // Verify ZIP/APK magic header (PK\x03\x04) so captive-portal HTML is rejected
                val header = ByteArray(4)
                val headerRead = FileInputStream(tmpFile).use { it.read(header) }
                if (headerRead < 4 ||
                    header[0] != 0x50.toByte() ||
                    header[1] != 0x4B.toByte() ||
                    header[2] != 0x03.toByte() ||
                    header[3] != 0x04.toByte()
                ) {
                    tmpFile.delete()
                    throw IllegalStateException("الملف المحمّل ليس ملف APK صالحاً.")
                }

                if (apkFile.exists()) apkFile.delete()
                if (!tmpFile.renameTo(apkFile)) {
                    tmpFile.copyTo(apkFile, overwrite = true)
                    tmpFile.delete()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
                    withContext(Dispatchers.Main) {
                        val settingsIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${appContext.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { appContext.startActivity(settingsIntent) }
                    }
                    throw IllegalStateException("يرجى تفعيل صلاحية «تثبيت التطبيقات غير المعروفة» ثم إعادة الضغط على التحديث.")
                }

                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    appContext.startActivity(installIntent)
                }
            }
        } finally {
            downloadMutex.unlock()
        }
    }
}
