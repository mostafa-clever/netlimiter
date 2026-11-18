package com.wifiguard.limiter.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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

    fun getInstalledVersionCode(context: Context): Long {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(1L)
    }

    suspend fun checkLatestRelease(
        context: Context,
        repoPath: String = DEFAULT_REPO
    ): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanRepo = repoPath.trim().ifBlank { DEFAULT_REPO }
                .removePrefix("https://github.com/")
                .trim('/')
            if (!cleanRepo.contains("/")) {
                throw IllegalArgumentException("أدخل اسم المستودع بصيغة: username/repo")
            }

            val localVersion = getInstalledVersionCode(context)

            // Query the exact `latest` tag first (works even when release is overwritten), fallback to /releases/latest
            val obj = fetchReleaseJson("https://api.github.com/repos/$cleanRepo/releases/tags/latest")
                ?: fetchReleaseJson("https://api.github.com/repos/$cleanRepo/releases/latest")
                ?: throw IllegalStateException("تعذر الوصول لإصدارات GitHub.")

            val tag = obj.optString("tag_name", "latest")
            val publishedAt = obj.optString("published_at", "")
            val notes = obj.optString("body", "")

            // Parse `build:<number>` embedded by GitHub Actions
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

            val hasUpdate = if (remoteBuild > 0L) remoteBuild > localVersion else true

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

    private fun fetchReleaseJson(urlStr: String): JSONObject? {
        return runCatching {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WifiGuard-Android")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            conn.disconnect()
            JSONObject(text)
        }.getOrNull()
    }

    suspend fun downloadAndInstallApk(context: Context, downloadUrl: String = DIRECT_APK_URL): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apkFile = File(context.cacheDir, "WifiGuard-update.apk")
            if (apkFile.exists()) apkFile.delete()

            val url = URL(downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "WifiGuard-Android")
            }
            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        }
    }
}
