package com.livetv.app.ui

import android.app.Activity
import com.livetv.app.data.Item
import com.livetv.app.epg.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProgramDetailsDialog {
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

        val rows = listOf(
            Row("Watch channel", accentTitle = true, onClick = { onWatch() }),
            Row("Set reminder", onClick = {
                Dialogs.message(activity, "Reminder Set", "You will be alerted before '${program.title}' starts.")
            }),
            Row("Record", onClick = {
                Dialogs.message(activity, "Scheduled Recording", "'${program.title}' is scheduled to record.")
            })
        )

        Dialogs.menu(activity, program.title, rows, 460f)
    }
}
