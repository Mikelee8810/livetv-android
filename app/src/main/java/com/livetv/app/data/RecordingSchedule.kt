package com.livetv.app.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** A recording booked for a future programme. Times are epoch ms, padding already applied. */
data class ScheduledRecording(
    val startMs: Long,
    val endMs: Long,
    val channelKey: String,
    val channelName: String,
    val title: String
) {
    val id: String get() = "$channelKey@$startMs"
    fun toJson() = JSONObject().put("s", startMs).put("e", endMs).put("k", channelKey).put("n", channelName).put("t", title)
    companion object {
        fun fromJson(o: JSONObject) = ScheduledRecording(o.getLong("s"), o.getLong("e"), o.getString("k"), o.getString("n"), o.getString("t"))
    }
}

/**
 * Scheduled recordings: stored in Prefs, armed with AlarmManager, and
 * started by [RecordingService] so they run with the app closed.
 * Alarms are re-armed after a reboot by the existing BootReceiver.
 */
object RecordingSchedule {
    private const val KEY = "scheduledRecordings"
    private const val TAG = "RecordingSchedule"

    fun all(prefs: Prefs): List<ScheduledRecording> = try {
        val arr = JSONArray(prefs.str(KEY, "[]"))
        (0 until arr.length()).map { ScheduledRecording.fromJson(arr.getJSONObject(it)) }
            .filter { it.endMs > System.currentTimeMillis() }
            .sortedBy { it.startMs }
    } catch (e: Exception) { emptyList() }

    private fun save(prefs: Prefs, list: List<ScheduledRecording>) {
        prefs.put(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    /** Book [title] on [item] from [startMs] to [endMs], with the user's padding. */
    fun add(ctx: Context, prefs: Prefs, item: Item, title: String, startMs: Long, endMs: Long): ScheduledRecording {
        val rec = ScheduledRecording(
            startMs - prefs.recPaddingBefore * 60_000L,
            endMs + prefs.recPaddingAfter * 60_000L,
            item.key, item.name, title
        )
        save(prefs, all(prefs).filter { it.id != rec.id } + rec)
        arm(ctx, rec)
        return rec
    }

    fun isScheduled(prefs: Prefs, item: Item, startMs: Long) =
        all(prefs).any { it.channelKey == item.key && it.startMs <= startMs && it.endMs > startMs }

    fun cancel(ctx: Context, prefs: Prefs, rec: ScheduledRecording) {
        save(prefs, all(prefs).filter { it.id != rec.id })
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, rec))
    }

    fun rescheduleAll(ctx: Context, prefs: Prefs) {
        val list = all(prefs)
        save(prefs, list) // drops finished entries
        list.forEach { arm(ctx, it) }
    }

    private fun pending(ctx: Context, rec: ScheduledRecording): PendingIntent {
        val i = Intent(ctx, RecordingAlarmReceiver::class.java)
            .setAction("com.livetv.app.RECORD")
            .putExtra("id", rec.id)
        return PendingIntent.getBroadcast(ctx, rec.id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(ctx: Context, rec: ScheduledRecording) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Fire 30 s early so the stream is tuned by the start time.
        val at = maxOf(System.currentTimeMillis() + 1000L, rec.startMs - 30_000L)
        val pi = pending(ctx, rec)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun byId(prefs: Prefs, id: String) = all(prefs).firstOrNull { it.id == id }
}

/** Alarm target: hands the booking to the foreground service. */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        RecordingService.start(context, id)
    }
}

/**
 * Foreground service that keeps the process alive while any recording
 * runs (scheduled or started by hand), so recordings continue with the
 * app closed or the screen off. Stops itself when nothing is recording.
 */
class RecordingService : Service() {
    companion object {
        private const val CHANNEL = "recordings"
        private const val NOTIF_ID = 4711

        /** Start a scheduled booking by id, or just keep alive for running recordings when id is null. */
        fun start(ctx: Context, scheduledId: String?) {
            val i = Intent(ctx, RecordingService::class.java)
            if (scheduledId != null) i.putExtra("id", scheduledId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                Log.w("RecordingService", "Could not start service", e)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
        }
        Recorder.listeners.add(listener)
    }

    private fun notification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        return b.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Recording")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        val id = intent?.getStringExtra("id")
        if (id != null) startScheduled(id) else refresh()
        return START_NOT_STICKY
    }

    private fun startScheduled(id: String) {
        Repo.ensureInit(this)
        val prefs = Repo.prefs
        val rec = RecordingSchedule.byId(prefs, id)
        if (rec == null) { refresh(); return }
        RecordingSchedule.cancel(this, prefs, rec) // one-shot: remove the booking
        val begin = {
            val item = Repo.byKey(rec.channelKey)
            if (item == null) {
                Log.w("RecordingService", "Channel ${rec.channelName} not found for scheduled recording")
                refresh()
            } else {
                Thread {
                    try { com.livetv.app.OnDeviceStreamServer.ensureStarted() } catch (e: Exception) { Log.w("RecordingService", "Stream server start failed", e) }
                    main.post { Recorder.start(this, item, rec.title, rec.endMs); refresh() }
                }.start()
            }
        }
        // With the app closed the channel list is not loaded yet.
        if (Repo.items.isEmpty()) Repo.reload { begin() } else begin()
    }

    private fun refresh() {
        main.removeCallbacks(stopIfIdle)
        val running = Recorder.activeList()
        if (running.isEmpty()) {
            // Small grace period so a recording that is just being
            // started does not race the stop.
            main.postDelayed(stopIfIdle, 5000L)
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification(running.joinToString { "${it.title} (${it.channelName})" }))
        }
    }

    private val stopIfIdle = Runnable {
        if (Recorder.activeList().isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Recorder.listeners.remove(listener)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
