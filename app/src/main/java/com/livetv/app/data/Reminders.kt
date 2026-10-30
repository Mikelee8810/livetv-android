package com.livetv.app.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.livetv.app.MainActivity
import com.livetv.app.R
import java.time.Instant

/**
 * Program reminders, stored in Prefs and fired by AlarmManager.
 *
 * This replaces the old fake flow, where "Set reminder" and "Record" only
 * showed a success dialog and nothing was ever saved or scheduled.
 * Recordings were removed for the same reason: the app never recorded
 * anything, so the UI no longer claims it does.
 */
data class Reminder(val startMs: Long, val channelKey: String, val channelName: String, val title: String) {
    fun encode(): String = listOf(startMs.toString(), channelKey, channelName, title)
        .joinToString("\u001F") { it.replace("\u001F", " ") }

    companion object {
        fun decode(raw: String): Reminder? {
            val parts = raw.split("\u001F")
            if (parts.size < 4) return null
            val start = parts[0].toLongOrNull() ?: return null
            return Reminder(start, parts[1], parts[2], parts[3])
        }
    }
}

object Reminders {
    private const val KEY = "reminders"

    fun all(prefs: Prefs): List<Reminder> =
        prefs.set(KEY).mapNotNull { Reminder.decode(it) }
            .filter { it.startMs > System.currentTimeMillis() - 60_000L }
            .sortedBy { it.startMs }

    fun add(ctx: Context, prefs: Prefs, reminder: Reminder) {
        val entries = prefs.set(KEY).toMutableSet()
        entries.removeAll { Reminder.decode(it)?.let { r -> r.startMs == reminder.startMs && r.channelKey == reminder.channelKey } == true }
        entries.add(reminder.encode())
        prefs.put(KEY, entries)
        schedule(ctx, prefs, reminder)
    }

    fun cancel(ctx: Context, prefs: Prefs, reminder: Reminder) {
        val entries = prefs.set(KEY).toMutableSet()
        entries.removeAll { Reminder.decode(it)?.let { r -> r.startMs == reminder.startMs && r.channelKey == reminder.channelKey } == true }
        prefs.put(KEY, entries)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, reminder))
    }

    private fun requestCode(reminder: Reminder): Int =
        (reminder.startMs / 1000L).toInt() xor reminder.channelKey.hashCode()

    private fun pendingIntent(ctx: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra("title", reminder.title)
            putExtra("channel", reminder.channelName)
            putExtra("channelKey", reminder.channelKey)
            putExtra("autoSwitch", false)
        }
        return PendingIntent.getBroadcast(
            ctx, requestCode(reminder), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(ctx: Context, prefs: Prefs, reminder: Reminder) {
        val fireAt = reminder.startMs - prefs.remindLeadMinutes * 60_000L
        if (fireAt <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, reminder)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }
    }

    /** Re-arm every saved reminder, e.g. after a reboot. */
    fun rescheduleAll(ctx: Context, prefs: Prefs) {
        all(prefs).forEach { schedule(ctx, prefs, it) }
    }
}

/** Re-arms saved reminders after a reboot (alarms do not survive one). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.rescheduleAll(context, Prefs(context))
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Program starting"
        val channel = intent.getStringExtra("channel") ?: ""
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("reminders", "Program reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("channelKey", intent.getStringExtra("channelKey"))
        }
        val pi = PendingIntent.getActivity(
            context, title.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(if (channel.isNotEmpty()) "Starting soon on $channel" else "Starting soon")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(title.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the reminder stays listed in the app.
        }
    }
}
