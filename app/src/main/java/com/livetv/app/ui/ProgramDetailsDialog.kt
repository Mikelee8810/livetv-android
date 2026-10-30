package com.livetv.app.ui

import android.app.Activity
import com.livetv.app.data.Item
import com.livetv.app.epg.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProgramDetailsDialog {
    /** Set by MainActivity: seeks the playing channel back to a programme's
     *  start; returns false when that moment left the DVR window. */
    var catchUpHandler: ((Item, Program) -> Boolean)? = null

    /** Set by MainActivity: jumps the playing channel back to the live edge. */
    var liveHandler: ((Item) -> Unit)? = null

    fun show(activity: Activity, item: Item, program: Program, onWatch: () -> Unit) {
        val fmt = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.US)
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val timeStr = "${fmt.format(Date(program.start.toEpochMilli()))} – ${timeFmt.format(Date(program.end.toEpochMilli()))}"

        val desc = buildString {
            append(timeStr).append("\n\n")
            if (!program.category.isNullOrBlank()) {
                append("Genre: ").append(program.category).append("\n")
            }
            if (!program.rating.isNullOrBlank()) {
                append("Rating: ").append(program.rating).append("\n")
            }
            if (!program.subtitle.isNullOrBlank()) {
                append("Episode: ").append(program.subtitle).append("\n\n")
            } else {
                append("\n")
            }
            if (!program.description.isNullOrBlank()) {
                append(program.description)
            } else {
                append("No program description available.")
            }
        }

        val nowMs = System.currentTimeMillis()
        val startMs = program.start.toEpochMilli()
        val endMs = program.end.toEpochMilli()
        val rows = mutableListOf(
            Row("Watch channel", accentTitle = true, onClick = { onWatch() })
        )
        // Catch-up (Ondo-style): live programmes can restart, past programmes
        // can be watched from the start while they are in the DVR window.
        if (startMs <= nowMs) {
            val live = nowMs < endMs
            rows.add(Row(if (live) "Restart from beginning" else "Watch from start", onClick = {
                val ok = catchUpHandler?.invoke(item, program) ?: false
                if (!ok) {
                    Dialogs.message(activity, "Not Available", "That moment has already left this stream's replay window. DaddyLive windows are short, so catch-up only reaches back a few minutes.")
                }
            }))
            if (live) {
                rows.add(Row("Back to live", onClick = { liveHandler?.invoke(item) }))
            }
        }
        rows.add(
            Row("Set reminder", onClick = {
                // Real reminder: saved to Prefs and armed in AlarmManager.
                // (The old row only showed a success dialog and saved nothing,
                // and the even older "Record" row promised recordings the app
                // never made, so it is gone.)
                val prefs = com.livetv.app.data.Repo.prefs
                com.livetv.app.data.Reminders.add(
                    activity, prefs,
                    com.livetv.app.data.Reminder(
                        program.start.toEpochMilli(), item.key, item.name, program.title
                    )
                )
                Dialogs.message(activity, "Reminder Set", "You will be alerted ${prefs.remindLeadMinutes} minutes before '${program.title}' starts on ${item.name}. Find it under Main Menu, Reminders.")
            })
        )

        Dialogs.menu(activity, program.title, rows, 460f)
    }
}
