package com.livetv.app.data

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.livetv.app.BuildConfig
import com.livetv.app.ui.Dialogs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * In-app updater backed by GitHub Releases.
 *
 * The release workflow publishes every signed build as a GitHub Release with
 * a `livetv-release.apk` asset, so the app can check the repo's latest
 * release, offer the update, download the APK, and hand it to the system
 * installer. Android always asks the user on the install screen itself -
 * that part is the system's rule, not ours - but everything up to that tap
 * is automatic. Checks run on start (Settings > Other > Auto-check) and on
 * demand from Settings > Other > Check for updates.
 */
object Updater {
    private const val LATEST_URL =
        "https://api.github.com/repos/Mikelee8810/livetv-android/releases/latest"
    private const val ASSET_NAME = "livetv-release.apk"

    data class ReleaseInfo(val tag: String, val version: String, val apkUrl: String, val notes: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    // A download is single-flight of its own (audit U1): the check
    // flag is released when the prompt shows, so without this a
    // second check could start a second download onto the same file.
    private val downloadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Numeric version compare: "4.2.2" > "4.2.1"; a leading "v" is ignored. */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    fun fetchLatest(): ReleaseInfo? {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("User-Agent", "LiveTV-Android/" + BuildConfig.VERSION_NAME)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == ASSET_NAME) {
                    val url = asset.optString("browser_download_url")
                    if (url.isBlank()) return null
                    return ReleaseInfo(tag, tag.removePrefix("v"), url, json.optString("body").take(600))
                }
            }
            return null
        }
    }

    /**
     * Check for an update and, if one exists, offer it. Silent when there is
     * nothing to do unless [manual] is true (the Settings button).
     */
    private val checkInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun checkAndPrompt(activity: Activity, manual: Boolean) {
        // One check at a time: the startup auto-check and a Settings tap
        // racing each other used to double-prompt and double-download
        // the same APK file, corrupting it.
        if (!checkInFlight.compareAndSet(false, true)) return
        thread(name = "update-check") {
            val info = try { fetchLatest() } catch (e: Exception) { null }
            activity.runOnUiThread {
                checkInFlight.set(false)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    info == null -> if (manual) Dialogs.message(
                        activity, "Updates", "Could not reach GitHub to check for updates."
                    )
                    !isNewer(info.version, BuildConfig.VERSION_NAME) -> if (manual) Dialogs.message(
                        activity, "Updates", "You are up to date on version " + BuildConfig.VERSION_NAME + "."
                    )
                    else -> Dialogs.confirm(
                        activity,
                        "Update available",
                        "LiveTV " + info.tag + " is ready. You have " + BuildConfig.VERSION_NAME + "." +
                            if (info.notes.isNotBlank()) "\n\n" + info.notes else "",
                        yes = "Update now"
                    ) { downloadAndInstall(activity, info) }
                }
            }
        }
    }

    private fun downloadAndInstall(activity: Activity, info: ReleaseInfo) {
        if (!downloadInFlight.compareAndSet(false, true)) {
            Dialogs.message(activity, "Update", "An update download is already running.")
            return
        }
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "livetv-" + info.tag + ".apk")
        val part = File(dir, file.name + ".part")
        // Old update files do not accumulate (audit U3).
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("livetv-") && f.name != file.name && f.name != part.name) f.delete()
        }
        val progress = ProgressDialog(activity).apply {
            setTitle("Downloading LiveTV " + info.tag)
            setMessage("Getting the update ready...")
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        thread(name = "update-download") {
            var ok = false
            var read = 0L
            var total = -1L
            try {
                val req = Request.Builder().url(info.apkUrl)
                    .header("User-Agent", "LiveTV-Android/" + BuildConfig.VERSION_NAME)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body
                    if (resp.isSuccessful && body != null) {
                        total = body.contentLength()
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed && total > 0) {
                                progress.isIndeterminate = false
                                progress.max = 100
                            }
                        }
                        body.byteStream().use { input ->
                            // Download to .part and rename (audit U1):
                            // a truncated write must never sit under
                            // the final name looking installable.
                            FileOutputStream(part).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    read += n
                                    if (total > 0) {
                                        val pct = (read * 100 / total).toInt()
                                        activity.runOnUiThread {
                                            if (!activity.isFinishing && !activity.isDestroyed) progress.progress = pct
                                        }
                                    }
                                }
                            }
                        }
                        // Verify the whole body arrived: a connection
                        // that ends early yields a clean EOF and, with
                        // only a length > 0 check, a truncated APK that
                        // dead-ends at the installer's parse error.
                        ok = part.length() > 0 && (total <= 0L || read == total) && part.renameTo(file)
                    }
                }
            } catch (e: Exception) {
                ok = false
            }
            activity.runOnUiThread {
                downloadInFlight.set(false)
                // Dismiss before the finishing guard (audit U2): the
                // old order leaked the dialog window when the activity
                // was going away.
                try { progress.dismiss() } catch (e: Exception) { }
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (ok) install(activity, file)
                else Dialogs.message(activity, "Update failed", "The download did not finish. Try again from Settings.")
            }
        }
    }

    private fun install(activity: Activity, file: File) {
        // First install from the app itself needs the system's one-time
        // "allow installs from LiveTV" grant; send the user straight there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Dialogs.message(
                activity, "One quick permission",
                "Android needs you to allow LiveTV to install its own updates once. Turn it on, come back, and check for updates again."
            )
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.packageName)
            )
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                Dialogs.message(activity, "Updates", "Could not open the install permission screen on this device.")
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Some TV builds lack an installer activity (audit U4).
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Dialogs.message(activity, "Update failed", "No installer is available on this device.")
        }
    }
}
