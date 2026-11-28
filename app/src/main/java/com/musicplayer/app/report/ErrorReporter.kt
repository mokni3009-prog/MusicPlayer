package com.musicplayer.app.report

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Central error reporting for Zeno Play (v1.0.3).
 *
 * Every kind of error in the app goes through here — not just crashes:
 *  - "crash":      uncaught exceptions (global handler, installed by ZenoPlayApp)
 *  - "playback":   a song / format that will not play (PlaybackService.onPlayerError)
 *  - "library":    MediaStore scan / load failures (MusicRepository)
 *  - "permission": audio / notification permission denied (MainActivity)
 *  - "update":     update check / download / install failures (UpdateChecker, receiver)
 *  - "database":   local playlists / favorites storage failures (PlaylistStore, FavoritesStore)
 *  - "notification": background-playback / notification failures
 *  - "network":    other network failures
 *  - "error":      any other in-app error
 *
 * Reports are saved locally first, then uploaded automatically to the
 * Zeno crash-report server (Cloudflare Worker + D1) — immediately when
 * possible, otherwise on the next launch (flushPendingReports). No user
 * action is ever needed.
 *
 * Privacy: reports contain the error type, message, stack trace, app
 * version, device model / brand, Android version and a small context map.
 * They NEVER contain file paths, URIs, or any personal data.
 *
 * This class never throws: every public entry point is fully guarded, so
 * reporting can never crash the app it is trying to report about.
 */
object ErrorReporter {

    const val TYPE_CRASH = "crash"
    const val TYPE_PLAYBACK = "playback"
    const val TYPE_LIBRARY = "library"
    const val TYPE_PERMISSION = "permission"
    const val TYPE_UPDATE = "update"
    const val TYPE_DATABASE = "database"
    const val TYPE_NOTIFICATION = "notification"
    const val TYPE_NETWORK = "network"
    const val TYPE_ERROR = "error"
    /** Temporary self-test reports (see SelfTest.kt — deletable module). */
    const val TYPE_SELF_TEST = "self-test"

    private const val TAG = "ErrorReporter"
    private const val ENDPOINT = "https://zeno-crash-reporter.streammaster.workers.dev/report"
    private const val PENDING_DIR = "pending_reports"
    private const val MAX_PENDING = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appVersion: String = "?"

    @Volatile
    private var versionCode: Long = 0

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** Call once from the Application class, before anything else. */
    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                appVersion = info.versionName ?: "?"
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            } catch (e: Exception) {
                Log.w(TAG, "package info unavailable", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "init failed", e)
        }
    }

    /**
     * Installs the global crash handler. It saves the crash report
     * synchronously (the process is dying — no background work allowed),
     * then hands the crash to the previous default handler so Android
     * shows the normal crash dialog and kills the process as usual.
     * The saved report is uploaded on the next launch.
     */
    fun installCrashHandler() {
        try {
            if (defaultHandler == null) {
                defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            }
            Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                try {
                    saveReportSync(
                        TYPE_CRASH,
                        "Uncaught exception: ${throwable?.javaClass?.name}: ${throwable?.message}",
                        throwable,
                        emptyMap()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "crash report save failed", e)
                }
                // Let the system handle the crash normally (dialog + process kill).
                try {
                    defaultHandler?.uncaughtException(Thread.currentThread(), throwable)
                } catch (e: Exception) {
                    // Last resort: nothing else we can do.
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "installCrashHandler failed", e)
        }
    }

    /** Log any in-app error. Saves locally and tries to upload right away. */
    fun log(
        type: String,
        message: String,
        throwable: Throwable? = null,
        context: Map<String, Any?> = emptyMap()
    ) {
        try {
            val file = saveReportSync(type, message, throwable, context) ?: return
            scope.launch { uploadFile(file) }
        } catch (e: Exception) {
            Log.w(TAG, "log failed", e)
        }
    }

    /** Upload every report saved locally (called on launch). */
    fun flushPendingReports() {
        try {
            scope.launch {
                val dir = pendingDir() ?: return@launch
                val files = try {
                    dir.listFiles()?.sortedBy { it.lastModified() } ?: return@launch
                } catch (e: Exception) {
                    return@launch
                }
                for (file in files) {
                    uploadFile(file)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "flush failed", e)
        }
    }

    // ----- internals -----

    private fun pendingDir(): File? {
        return try {
            val ctx = appContext ?: return null
            File(ctx.filesDir, PENDING_DIR).apply { if (!exists()) mkdirs() }
        } catch (e: Exception) {
            null
        }
    }

    /** Builds the report JSON and writes it to the pending folder. Returns the file. */
    private fun saveReportSync(
        type: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, Any?>
    ): File? {
        return try {
            val dir = pendingDir() ?: return null
            pruneIfNeeded(dir)
            val payload = JSONObject().apply {
                put("type", type)
                put("message", message.take(2000))
                put("stacktrace", throwable?.let { Log.getStackTraceString(it).take(12000) })
                put("app_version", appVersion)
                put("version_code", versionCode)
                put("device_model", Build.MODEL ?: "?")
                put("device_brand", Build.BRAND ?: "?")
                put("android_version", Build.VERSION.RELEASE ?: "?")
                put("sdk_int", Build.VERSION.SDK_INT)
                put("context", JSONObject(context.mapValues { (_, v) -> v?.toString() ?: JSONObject.NULL }))
            }
            val name = "${System.currentTimeMillis()}_${type}_${UUID.randomUUID().toString().take(8)}.json"
            val file = File(dir, name)
            file.writeText(payload.toString())
            file
        } catch (e: Exception) {
            Log.w(TAG, "saveReport failed", e)
            null
        }
    }

    /** Keeps the pending folder bounded: drops the oldest files past the cap. */
    private fun pruneIfNeeded(dir: File) {
        try {
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size >= MAX_PENDING) {
                files.take(files.size - MAX_PENDING + 1).forEach { it.delete() }
            }
        } catch (e: Exception) {
            // Ignore pruning errors.
        }
    }

    /** POSTs one saved report; deletes the file on success (2xx). */
    private fun uploadFile(file: File) {
        try {
            val body = file.readText()
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                file.delete()
            } else {
                Log.w(TAG, "upload failed: HTTP $code for ${file.name}")
            }
        } catch (e: Exception) {
            // Offline or server unreachable: keep the file, retry on next launch.
            Log.w(TAG, "upload failed for ${file.name}", e)
        }
    }
}
