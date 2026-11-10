package com.livetv.app.data

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Live recorder. Follows a channel's HLS playlist (through the same
 * local stream server the player uses, so decryption and route
 * handling are shared) and appends every new segment to one .ts file.
 * A .ts file is playable as soon as the first segment lands, so a
 * recording can be watched while it is still being made.
 *
 * Recordings run while the app process is alive. They live in the
 * app's own Movies folder and are removed with the app.
 */
object Recorder {
    private const val TAG = "Recorder"
    private const val SERVER = "http://127.0.0.1:48913"

    class Active(
        val id: String,
        val channelName: String,
        val title: String,
        val file: File,
        val endMs: Long
    ) {
        @Volatile var stop = false
        @Volatile var bytes = 0L
        @Volatile var segments = 0
        @Volatile var error: String? = null
    }

    data class Saved(val file: File, val sizeBytes: Long, val modifiedMs: Long, val recording: Boolean) {
        val title: String get() = file.nameWithoutExtension.replace('_', ' ')
    }

    private val active = ConcurrentHashMap<String, Active>()
    private val main = Handler(Looper.getMainLooper())
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun fire() = main.post { listeners.forEach { it() } }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "Recordings").apply { mkdirs() }

    fun activeList(): List<Active> = active.values.sortedBy { it.channelName }
    fun isRecording(channelName: String) = active.values.any { it.channelName == channelName && !it.stop }

    fun saved(ctx: Context): List<Saved> {
        val live = active.values.map { it.file.absolutePath }.toSet()
        return (dir(ctx).listFiles { f -> f.isFile && f.extension == "ts" } ?: emptyArray())
            .map { Saved(it, it.length(), it.lastModified(), it.absolutePath in live) }
            .sortedByDescending { it.modifiedMs }
    }

    fun delete(ctx: Context, s: Saved): Boolean {
        active.values.firstOrNull { it.file.absolutePath == s.file.absolutePath }?.stop = true
        val ok = s.file.delete()
        fire()
        return ok
    }

    /** Delete finished recordings older than the Auto-delete setting. */
    fun purgeOld(ctx: Context) {
        val days = Repo.prefs.recKeepDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        saved(ctx).filter { !it.recording && it.modifiedMs < cutoff }.forEach { it.file.delete() }
        fire()
    }

    fun stop(id: String) { active[id]?.stop = true; fire() }

    /** Start recording [item] until [endMs] (epoch ms). Returns the recording, or null if already recording it. */
    fun start(ctx: Context, item: Item, title: String, endMs: Long): Active? {
        if (isRecording(item.name)) return null
        purgeOld(ctx)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val safe = "${item.name} ${title}".replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().replace(' ', '_').take(80)
        val file = File(dir(ctx), "${stamp}_$safe.ts")
        val rec = Active(file.name, item.name, title, file, endMs)
        active[rec.id] = rec
        fire()
        // Foreground service keeps the recording alive if the app is
        // closed or the TV goes to sleep.
        RecordingService.start(ctx, null)

        var url = item.url
        if (url.startsWith("/")) url = SERVER + url
        if (url.contains("127.0.0.1:48913") && url.contains("manifest") && !url.contains("player=")) {
            url += (if (url.contains("?")) "&" else "?") + "player=0"
        }
        val ua = Repo.prefs.userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" }

        thread(name = "recorder", isDaemon = true) {
            try {
                record(rec, url, ua)
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed: ${rec.id}", e)
                rec.error = e.message ?: e.javaClass.simpleName
            } finally {
                active.remove(rec.id)
                if (rec.segments == 0) rec.file.delete()
                fire()
            }
        }
        return rec
    }

    private fun get(url: String, ua: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun resolve(base: String, ref: String): String = URI(base).resolve(ref.trim()).toString()

    private fun record(rec: Active, startUrl: String, ua: String) {
        var playlistUrl = startUrl
        val seen = LinkedHashSet<String>()
        var initWritten = false
        var failures = 0
        FileOutputStream(rec.file, true).use { out ->
            while (!rec.stop && System.currentTimeMillis() < rec.endMs) {
                var targetDuration = 6
                try {
                    var text = String(get(playlistUrl, ua))
                    // Multivariant playlist: follow the highest-bandwidth variant.
                    if (text.contains("#EXT-X-STREAM-INF")) {
                        val lines = text.lines()
                        var best = -1L; var bestUrl: String? = null
                        for (i in lines.indices) {
                            val l = lines[i]
                            if (l.startsWith("#EXT-X-STREAM-INF")) {
                                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                                if (next != null && bw >= best) { best = bw; bestUrl = resolve(playlistUrl, next) }
                            }
                        }
                        playlistUrl = bestUrl ?: throw java.io.IOException("No variant in playlist")
                        text = String(get(playlistUrl, ua))
                    }
                    Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { targetDuration = it.coerceIn(1, 15) }
                    if (!initWritten) {
                        Regex("#EXT-X-MAP:.*URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.let {
                            out.write(get(resolve(playlistUrl, it), ua))
                        }
                        initWritten = true
                    }
                    for (line in text.lines()) {
                        if (rec.stop) break
                        if (line.isBlank() || line.startsWith("#")) continue
                        val segUrl = resolve(playlistUrl, line)
                        if (!seen.add(segUrl)) continue
                        val bytes = get(segUrl, ua)
                        out.write(bytes); out.flush()
                        rec.bytes += bytes.size; rec.segments++
                        while (seen.size > 200) seen.remove(seen.first())
                    }
                    failures = 0
                    if (text.contains("#EXT-X-ENDLIST")) break
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "Recorder poll failed ($failures): ${e.message}")
                    // Give up after ~2 minutes of consecutive failures.
                    if (failures >= 20) throw e
                }
                // Poll at about half the segment length, as HLS clients do.
                val wait = (targetDuration * 500L).coerceIn(1000L, 6000L)
                var waited = 0L
                while (waited < wait && !rec.stop) { Thread.sleep(250L); waited += 250L }
            }
        }
    }
}
