package com.livetv.app.ui

import android.app.Activity
import com.livetv.app.data.Item
import com.livetv.app.data.Repo
import com.livetv.app.epg.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChannelMenu {
    fun show(
        activity: Activity,
        item: Item,
        program: Program?,
        onWatch: () -> Unit,
        onRefresh: () -> Unit
    ) {
        val isFav = Repo.isFav(item)
        val curProg = program ?: Repo.current(item)

        val rows = mutableListOf<Row>()
        rows.add(Row("Watch fullscreen", accentTitle = true, onClick = {
            onWatch()
        }))

        rows.add(Row(if (isFav) "Remove from Favorites" else "Add to Favorites", onClick = {
            Repo.toggleFav(item)
            onRefresh()
        }))

        rows.add(Row("Hide channel", sub = "Can be restored in Appearance settings", onClick = {
            Dialogs.confirm(activity, "Hide Channel", "Hide '${item.name}' from your channel list?") {
                Repo.hide(item)
                onRefresh()
            }
        }))

        if (curProg != null) {
            val fmt = SimpleDateFormat("h:mm a", Locale.US)
            val times = "${fmt.format(Date(curProg.start.toEpochMilli()))} – ${fmt.format(Date(curProg.end.toEpochMilli()))}"
            rows.add(Row(
                "Program details",
                sub = "${curProg.title} ($times)",
                onClick = {
                    ProgramDetailsDialog.show(activity, item, curProg, onWatch)
                }
            ))
        }

        rows.add(Row(
            "Channel information",
            sub = "ID: ${item.ch.id} • Group: ${item.groups.joinToString(", ")}",
            onClick = {
                Dialogs.message(
                    activity,
                    item.name,
                    "Display Number: ${item.number}\n" +
                    "Category: ${item.ch.category.label}\n" +
                    "Group: ${item.groups.joinToString(", ")}\n" +
                    "Playback URL:\n${item.url}"
                )
            }
        ))

        Dialogs.menu(activity, item.name, rows, 420f)
    }
}
