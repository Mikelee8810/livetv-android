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
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).followRedirects(true).build()

    @Volatile var items: List<Item> = emptyList(); private set
    @Volatile var epg: EpgSnapshot? = null; private set
    // CopyOnWriteArrayList (audit R9): fire() iterates while other
    // threads add and remove listeners.
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var catalogEpg: List<String> = emptyList()
    // ConcurrentHashMap: written from reload/addPlaylist threads and
    // iterated from the main thread in epgSourceList(); a plain HashMap
    // raced into ConcurrentModificationException crashes.
    private val m3uEpg = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    // Reload sequencing (audit R1): only the newest reload may commit.
    private val reloadSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val epgRefreshRunnable = Runnable { if (prefs.epgOnStart) refreshEpg() }
    private val epgRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun init(ctx: Context) { app = ctx.applicationContext; prefs = Prefs(app) }
    private fun fire() = main.post { listeners.toList().forEach { it() } }

    // -- channels ----------------------------------------------------------------
    fun reload(done: (() -> Unit)? = null) {
        val mySeq = reloadSeq.incrementAndGet()
        thread(name = "reload") {
        m3uEpg.clear()
        val enabled = prefs.playlists().filter { it.enabled }
        // Builtin first, published on its own (audit R3): a slow or
        // trickling user playlist must not hold up the main guide.
        val ordered = enabled.sortedBy { if (it.id == "builtin") 0 else 1 }
        val out = ArrayList<Item>()
        var n = 1
        for ((pass, pl) in ordered.withIndex()) {
            val chans: List<Channel> = try {
                if (pl.id == "builtin") ChannelCatalog.loadWithReport(app).also { catalogEpg = it.epgSources }.channels
                else parseM3u(pl.id, fetch(pl.url))
            } catch (e: Exception) {
                android.util.Log.e("Repo", "Playlist ${pl.id} failed to load", e)
                // A playlist that fails this pass keeps its previous
                // channels: a transient fetch error must not silently
                // delete them from the guide.
                items.filter { it.playlistId == pl.id }.map { it.ch }
            }
            for (c in chans) out.add(Item(c, pl.id, n++, splitGroups(c.group)))
            if (pass == 0 && ordered.size > 1) {
                if (mySeq != reloadSeq.get()) return@thread
                val partial = sortItems(ArrayList(out))
                if (partial.isNotEmpty() || items.isEmpty()) items = partial
            }
        }
        // A superseded reload commits nothing (audit R1): a slow older
        // pass finishing last used to overwrite newer items.
        if (mySeq != reloadSeq.get()) return@thread
        // A bad reload must never blank the guide: if this pass produced no
        // channels at all but we already have some, keep the ones we have.
        val newItems = sortItems(out)
        if (newItems.isNotEmpty() || items.isEmpty()) items = newItems
        // EPG waits 15 s: its ~25 MB of downloads and parsing used to fight
        // the first playback for CPU right at app start. A single
        // posted runnable, cancelled and reposted per reload, so
        // reloads cannot stack refreshes (audit R2).
        main.post { done?.invoke(); fire(); if (prefs.epgOnStart) { main.removeCallbacks(epgRefreshRunnable); main.postDelayed(epgRefreshRunnable, 15000) } }
        // Background: refresh the live DaddyLive channel list when its cache
        // is stale, then reload once so new channels appear without a restart.
        thread(name = "live-catalog") {
            try { if (com.livetv.app.LiveCatalog.refreshIfStale(app)) reload() } catch (e: Exception) { }
        }
        }
    }

    private fun sortItems(l: List<Item>): List<Item> = when (prefs.sortMode) {
        "A–Z" -> l.sortedBy { it.name.lowercase() }
        else -> l
    }.mapIndexed { i, it -> Item(it.ch, it.playlistId, i + 1, it.groups) }

    // Local re-sort (audit Se7): a sort-mode change in settings used
    // to trigger a full network reload of every playlist.
    fun resort() {
        items = sortItems(ArrayList(items))
        fire()
    }

    fun splitGroups(g: String): List<String> =
        (if (prefs.groupsSplitSemicolon) g.split(';') else listOf(g)).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Uncategorized") }

    private fun fetch(url: String): String {
        val r = http.newCall(Request.Builder().url(url).header("User-Agent", prefs.userAgent.ifBlank { "LiveTV/4.0 (Android TV)" }).build()).execute()
        r.use {
            if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
            val body = it.body ?: throw java.io.IOException("Empty response body")
            // Size cap (audit R8): a hostile or broken playlist URL
            // must not be able to OOM the app.
            if (body.contentLength() > 20_000_000L) throw java.io.IOException("Playlist too large")
            val text = body.string()
            if (text.length > 20_000_000) throw java.io.IOException("Playlist too large")
            return text
        }
    }

    fun parseM3u(plId: String, text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
        var name = ""; var group = ""; var logo = ""; var tvg = ""
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
                // Stable ids (audit R7): positional ids remapped
                // favorites, hidden and recents whenever the provider
                // reordered its list.
                val stableId = "$plId-" + Integer.toHexString((line.hashCode() * 31 + name.hashCode()) and 0x7fffffff)
                out.add(Channel(stableId, tvg, name, group, logo, line, 1)); name = ""
            }
        }
        return out
    }

    fun addPlaylist(name: String, url: String, onDone: (String?) -> Unit) = thread {
        try {
            // Parse under the real id (audit R4): parsing as "probe"
            // polluted m3uEpg with entries no playlist owned.
            val id = "pl" + System.currentTimeMillis()
            val txt = fetch(url); val ch = parseM3u(id, txt)
            if (ch.isEmpty()) throw java.io.IOException("No channels found in playlist")
            prefs.savePlaylists(prefs.playlists() + PlaylistDef(id, name, url, "", true))
            main.post { reload { onDone(null) } }
        } catch (e: Exception) { main.post { onDone(e.message ?: "Failed") } }
    }
    fun removePlaylist(id: String) { m3uEpg.remove(id); prefs.savePlaylists(prefs.playlists().filter { it.id != id }); reload() }
    fun renamePlaylist(id: String, name: String) { prefs.savePlaylists(prefs.playlists().map { if (it.id == id) it.copy(name = name) else it }); fire() }
    fun togglePlaylist(id: String) { val nowDisabled = prefs.playlists().firstOrNull { it.id == id }?.enabled == true; if (nowDisabled) m3uEpg.remove(id); prefs.savePlaylists(prefs.playlists().map { if (it.id == id) it.copy(enabled = !it.enabled) else it }); reload() }
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
        val defaultGroupPriority = listOf(
            "USA Entertainment",
            "Movies & Cinema",
            "Kids & Animation",
            "Sports",
            "News",
            "Documentary & Nature",
            "UK Channels",
            "Canada Channels",
            "Lifestyle",
            "Music",
            "General 24/7",
            "International"
        )
        val order = if (prefs.groupOrder.isNotEmpty()) prefs.groupOrder else defaultGroupPriority
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
        // One refresh at a time (audit R2): stacked ~25 MB downloads
        // in the proxy's process were an OOM risk to the stream.
        if (!epgRunning.compareAndSet(false, true)) { done?.invoke("EPG refresh already running"); return }
        val chans = items.map { it.ch }
        val sources = epgSourceList()
        if (sources.isEmpty()) { epgRunning.set(false); prefs.epgLastStatus = "No EPG sources"; done?.invoke(prefs.epgLastStatus); return }
        if (!force && epg != null && System.currentTimeMillis() - prefs.epgLastUpdate < 30 * 60_000L) {
            epgRunning.set(false); done?.invoke(prefs.epgLastStatus); return
        }
        if (force) thread(name = "epg-clear") { java.io.File(app.cacheDir, "xmltv").deleteRecursively() }
        EpgRepository(app, sources).refresh(chans) { snap, report ->
            // The repository reports PROGRESS once per source
            // (audit N2): progressive snapshots fill the guide in,
            // but completion effects - clearing the in-flight guard,
            // stamping the freshness time, invoking done (which shows
            // the Settings dialog) - must run exactly once, on the
            // terminal report.
            val terminal = report.declaredSources == 0 ||
                report.successfulSources + report.failedSources >= report.declaredSources
            // Total failure must not wipe a good guide (audit N3):
            // the failure path posts an empty snapshot, and committing
            // it plus a fresh timestamp erased all programme data and
            // suppressed retry for 30 minutes.
            val totalFailure = report.declaredSources > 0 && report.successfulSources == 0 && report.parsedPrograms == 0
            if (!totalFailure) {
                epg = snap
                prefs.epgLastStatus = "${report.mappedChannels} channels, ${report.mappedPrograms} programs"
            } else if (terminal) {
                prefs.epgLastStatus = "EPG update failed - kept the previous guide"
            }
            if (terminal) {
                if (!totalFailure) prefs.epgLastUpdate = System.currentTimeMillis()
                epgRunning.set(false)
                done?.invoke(prefs.epgLastStatus)
            }
            fire()
        }
    }
    fun clearEpg() { epg = null; thread(name = "epg-clear") { java.io.File(app.cacheDir, "xmltv").deleteRecursively() }; prefs.epgLastStatus = "Cleared"; fire() }
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
