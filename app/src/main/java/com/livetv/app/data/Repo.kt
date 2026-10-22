package com.livetv.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.livetv.app.Channel
import com.livetv.app.ChannelCatalog
import com.livetv.app.epg.EpgRepository
import com.livetv.app.epg.EpgSnapshot
import com.livetv.app.epg.Program
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One channel as the UI sees it: stable key, display number, and the groups it belongs to. */
class Item(val ch: Channel, val playlistId: String, val number: Int, val groups: List<String>) {
    val key: String get() = "$playlistId|${ch.id}"
    val name: String get() = ch.name
    val url: String get() = ch.playbackUrl()
}

class GroupInfo(val id: String, val title: String, val count: Int, val builtin: Boolean)

object Repo {
    const val ALL = "All channels"
    const val FAVS = "Favorites"
    lateinit var app: Context
    lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()

    @Volatile var items: List<Item> = emptyList(); private set
    @Volatile var epg: EpgSnapshot? = null; private set
    val listeners = mutableListOf<() -> Unit>()
    private var catalogEpg: List<String> = emptyList()
    private val m3uEpg = mutableMapOf<String, List<String>>()
    private var epgChannels: List<Channel> = emptyList()

    fun init(ctx: Context) { app = ctx.applicationContext; prefs = Prefs(app) }
    private fun fire() = main.post { listeners.toList().forEach { it() } }

    // -- channels ----------------------------------------------------------------
    fun reload(done: (() -> Unit)? = null) = thread(name = "reload") {
        val out = ArrayList<Item>()
        var n = 1
        for (pl in prefs.playlists().filter { it.enabled }) {
            val chans: List<Channel> = try {
                if (pl.id == "builtin") ChannelCatalog.loadWithReport(app).also { catalogEpg = it.epgSources }.channels
                else parseM3u(pl.id, fetch(pl.url))
            } catch (e: Exception) { emptyList() }
            for (c in chans) out.add(Item(c, pl.id, n++, splitGroups(c.group)))
        }
        items = sortItems(out)
        main.post { done?.invoke(); fire(); if (prefs.epgOnStart) refreshEpg() }
    }

    private fun sortItems(l: List<Item>): List<Item> = when (prefs.sortMode) {
        "A–Z" -> l.sortedBy { it.name.lowercase() }
        else -> l
    }.mapIndexed { i, it -> Item(it.ch, it.playlistId, i + 1, it.groups) }

    fun splitGroups(g: String): List<String> =
        (if (prefs.groupsSplitSemicolon) g.split(';') else listOf(g)).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Uncategorized") }

    private fun fetch(url: String): String {
        val r = http.newCall(Request.Builder().url(url).header("User-Agent", prefs.userAgent.ifBlank { "LiveTV/4.0 (Android TV)" }).build()).execute()
        r.use { if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}"); return it.body!!.string() }
    }

    fun parseM3u(plId: String, text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
        var name = ""; var group = ""; var logo = ""; var tvg = ""; var idx = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTM3U")) { m3uEpg[plId] = ChannelCatalog.parseEpgSources(line) }
            else if (line.startsWith("#EXTINF")) {
                val a = attr.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                logo = a["tvg-logo"] ?: ""; group = a["group-title"] ?: ""; tvg = a["tvg-id"] ?: ""
                name = (a["tvg-name"]?.takeIf { it.isNotBlank() } ?: line.substringAfterLast(',').trim())
                name = line.substringAfterLast(',').trim().ifBlank { name }
            } else if (line.startsWith("#EXTGRP:")) group = line.removePrefix("#EXTGRP:").trim()
            else if (line.isNotEmpty() && !line.startsWith("#") && name.isNotEmpty()) {
                out.add(Channel("$plId-${idx++}", tvg, name, group, logo, line, 1)); name = ""
            }
        }
        return out
    }

    fun addPlaylist(name: String, url: String, onDone: (String?) -> Unit) = thread {
        try {
            val txt = fetch(url); val ch = parseM3u("probe", txt)
            if (ch.isEmpty()) throw java.io.IOException("No channels found in playlist")
            val id = "pl" + System.currentTimeMillis()
            prefs.savePlaylists(prefs.playlists() + PlaylistDef(id, name, url, "", true))
            main.post { reload { onDone(null) } }
        } catch (e: Exception) { main.post { onDone(e.message ?: "Failed") } }
    }
    fun removePlaylist(id: String) { prefs.savePlaylists(prefs.playlists().filter { it.id != id }); reload() }
    fun renamePlaylist(id: String, name: String) { prefs.savePlaylists(prefs.playlists().map { if (it.id == id) it.copy(name = name) else it }); fire() }
    fun togglePlaylist(id: String) { prefs.savePlaylists(prefs.playlists().map { if (it.id == id) it.copy(enabled = !it.enabled) else it }); reload() }
    fun channelCount(plId: String) = items.count { it.playlistId == plId }

    // -- groups ------------------------------------------------------------------
    fun visibleItems(): List<Item> {
        val hidden = prefs.hiddenChannels
        return items.filter { it.key !in hidden }
    }
    fun groupTitle(id: String) = prefs.groupRenames[id] ?: id
    fun groups(): List<GroupInfo> {
        val vis = visibleItems()
        val counts = linkedMapOf<String, Int>()
        vis.forEach { it.groups.forEach { g -> counts[g] = (counts[g] ?: 0) + 1 } }
        val hidden = prefs.hiddenGroups
        val order = prefs.groupOrder
        val named = counts.keys.filter { it !in hidden }.sortedWith(compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.lowercase() }))
        return buildList {
            add(GroupInfo(FAVS, FAVS, vis.count { it.key in prefs.favorites }, true))
            add(GroupInfo(ALL, ALL, vis.size, true))
            named.forEach { add(GroupInfo(it, groupTitle(it), counts[it] ?: 0, false)) }
        }
    }
    fun allGroupNames(): List<String> = items.flatMap { it.groups }.distinct().sortedWith(compareBy({ prefs.groupOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.lowercase() }))
    fun inGroup(id: String): List<Item> {
        val vis = visibleItems()
        return when (id) { ALL -> vis; FAVS -> vis.filter { it.key in prefs.favorites }; else -> vis.filter { id in it.groups } }
    }
    fun isFav(i: Item) = i.key in prefs.favorites
    fun toggleFav(i: Item): Boolean { val s = prefs.favorites.toMutableSet(); val on = if (i.key in s) { s.remove(i.key); false } else { s.add(i.key); true }; prefs.favorites = s; return on }
    fun hide(i: Item) { prefs.hiddenChannels = prefs.hiddenChannels + i.key; fire() }
    fun unhideAll() { prefs.hiddenChannels = emptySet<String>(); prefs.hiddenGroups = emptySet<String>(); fire() }
    fun byKey(k: String) = items.firstOrNull { it.key == k }
    fun touchRecent(i: Item) { prefs.recents = (listOf(i.key) + prefs.recents.filter { it != i.key }).take(30) }

    // -- EPG ---------------------------------------------------------------------
    fun epgSourceList(): List<String> = (catalogEpg + m3uEpg.values.flatten() + prefs.epgSources()).distinct()
    fun refreshEpg(force: Boolean = false, done: ((String) -> Unit)? = null) {
        val chans = items.map { it.ch }
        epgChannels = chans
        val sources = epgSourceList()
        if (sources.isEmpty()) { prefs.epgLastStatus = "No EPG sources"; done?.invoke(prefs.epgLastStatus); return }
        if (force) java.io.File(app.cacheDir, "xmltv").deleteRecursively()
        EpgRepository(app, sources).refresh(chans) { snap, report ->
            epg = snap
            prefs.epgLastUpdate = System.currentTimeMillis()
            prefs.epgLastStatus = "${report.mappedChannels} channels, ${report.mappedPrograms} programs"
            done?.invoke(prefs.epgLastStatus); fire()
        }
    }
    fun clearEpg() { epg = null; java.io.File(app.cacheDir, "xmltv").deleteRecursively(); prefs.epgLastStatus = "Cleared"; fire() }
    fun programs(i: Item): List<Program> = epg?.programsFor(i.ch) ?: emptyList()
    fun current(i: Item, now: Instant = Instant.now()): Program? = programs(i).firstOrNull { !it.start.isAfter(now) && it.end.isAfter(now) }
    fun next(i: Item, now: Instant = Instant.now()): Program? = programs(i).firstOrNull { it.start.isAfter(now) }

    // -- search ------------------------------------------------------------------
    class Hit(val item: Item, val program: Program?)
    fun search(q: String): List<Hit> {
        val t = q.trim().lowercase(); if (t.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        if (prefs.searchChannels) visibleItems().filter { it.name.lowercase().contains(t) }.forEach { out.add(Hit(it, null)) }
        if (prefs.searchPrograms) {
            val now = Instant.now()
            visibleItems().forEach { it2 ->
                programs(it2).filter { p -> p.end.isAfter(now) && (p.title.lowercase().contains(t)) }.take(2).forEach { p -> out.add(Hit(it2, p)) }
            }
        }
        return out.take(200)
    }
}
