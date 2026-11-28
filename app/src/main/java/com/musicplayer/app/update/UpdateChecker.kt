package com.musicplayer.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.musicplayer.app.report.ErrorReporter

data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val body: String?
)

/**
 * In-app updates from GitHub: checks the latest release, downloads the APK
 * and installs it with PackageInstaller — no uninstall, no manual download.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val OWNER = "mokni3009-prog"
    private const val REPO = "MusicPlayer"

    /** v1.0.15: set by the download dialog's "Annuler" button. The
     *  download loop notices it, deletes the partial file and returns
     *  null; the caller tells a user cancellation apart from a real
     *  failure through this flag (a cancellation stays silent). */
    @Volatile
    var downloadCancelled = false

    fun resetDownloadCancel() {
        downloadCancelled = false
    }

    fun cancelDownload() {
        downloadCancelled = true
    }

    /** True when [latest] (e.g. "v1.0.3") is newer than [current] (e.g. "1.0.2"). */
    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(l.size, c.size)
        for (i in 0 until n) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode != 200) {
                Log.w(TAG, "releases API: ${conn.responseCode}")
                ErrorReporter.log(
                    ErrorReporter.TYPE_UPDATE,
                    "Update check failed: GitHub releases API returned HTTP ${conn.responseCode}"
                )
                return@withContext null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name", "")
            if (tag.isBlank()) return@withContext null
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                val candidates = mutableListOf<Pair<String, String>>()
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk")) {
                        candidates.add(name to a.optString("browser_download_url"))
                    }
                }
                // ABI splits: the release holds one APK per architecture
                // (ZenoPlay-v<version>-<abi>.apk). Pick the one built for
                // this device's ABI. The exact "-<abi>.apk" match matters:
                // a plain "x86" substring would also match "x86_64".
                // The "-<abi>-release.apk" form is kept for old releases.
                outer@ for (abi in Build.SUPPORTED_ABIS) {
                    for ((name, url) in candidates) {
                        if (name.contains("-$abi.apk", ignoreCase = true) ||
                            name.contains("-$abi-release.apk", ignoreCase = true)
                        ) {
                            apkUrl = url
                            break@outer
                        }
                    }
                }
                // Fallback: single universal APK (old releases) or first asset.
                if (apkUrl.isNullOrBlank()) apkUrl = candidates.firstOrNull()?.second
            }
            if (apkUrl.isNullOrBlank()) return@withContext null
            ReleaseInfo(tag, tag.trimStart('v', 'V'), apkUrl, json.optString("body", null))
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatest failed", e)
            ErrorReporter.log(
                ErrorReporter.TYPE_UPDATE,
                "Update check failed (network or response error): ${e.message}",
                e
            )
            null
        }
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive")
            if (conn.responseCode != 200) {
                Log.w(TAG, "download: HTTP ${conn.responseCode}")
                ErrorReporter.log(
                    ErrorReporter.TYPE_UPDATE,
                    "Update download failed: HTTP ${conn.responseCode}"
                )
                return@withContext null
            }
            val total = conn.contentLengthLong
            if (downloadCancelled) return@withContext null
            val outFile = File(context.cacheDir, "zeno-play-update.apk")
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { out ->
                    val buf = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        if (downloadCancelled) break
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (downloadCancelled) {
                // v1.0.15: the user pressed "Annuler" — drop the partial
                // file so a later retry starts clean.
                try {
                    outFile.delete()
                } catch (e: Exception) {
                }
                return@withContext null
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "downloadApk failed", e)
            ErrorReporter.log(
                ErrorReporter.TYPE_UPDATE,
                "Update download failed: ${e.message}",
                e
            )
            null
        }
    }

    /** Installs the downloaded APK with PackageInstaller (no uninstall, no manual step). */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            apkFile.inputStream().use { input ->
                session.openWrite("package", 0, apkFile.length()).use { out ->
                    input.copyTo(out)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            )
            session.commit(pi.intentSender)
            session.close()
            true
        } catch (e: Exception) {
            Log.w(TAG, "installApk failed", e)
            ErrorReporter.log(
                ErrorReporter.TYPE_UPDATE,
                "Update install failed: ${e.message}",
                e
            )
            false
        }
    }
}
