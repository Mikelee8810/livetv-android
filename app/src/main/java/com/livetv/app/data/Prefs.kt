package com.livetv.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Every user-facing setting, with its default, in one place. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("livetv", Context.MODE_PRIVATE)

    fun str(k: String, d: String) = sp.getString(k, d) ?: d
    fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun int(k: String, d: Int) = sp.getInt(k, d)
    fun put(k: String, v: Any) = sp.edit().apply {
        when (v) { is String -> putString(k, v); is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is Long -> putLong(k, v); is Set<*> -> putStringSet(k, v.map { it.toString() }.toSet()) }
    }.apply()
    fun set(k: String): MutableSet<String> = (sp.getStringSet(k, emptySet()) ?: emptySet()).toMutableSet()

    // General
    var autoStartBoot get() = bool("autoStartBoot", false); set(v) = put("autoStartBoot", v)
    var autoStartWake get() = bool("autoStartWake", false); set(v) = put("autoStartWake", v)
    var resumeLast get() = bool("resumeLast", true); set(v) = put("resumeLast", v)
    var pipOnHome get() = bool("pipOnHome", false); set(v) = put("pipOnHome", v)
    var confirmExit get() = bool("confirmExit", false); set(v) = put("confirmExit", v)
    var userAgent get() = str("userAgent", ""); set(v) = put("userAgent", v)
    var udpProxy get() = str("udpProxy", ""); set(v) = put("udpProxy", v)
    var lastChannel get() = str("lastChannel", ""); set(v) = put("lastChannel", v)
    var lastGroup get() = str("lastGroup", "All channels"); set(v) = put("lastGroup", v)

    // EPG
    var epgKeepDays get() = int("epgKeepDays", 7); set(v) = put("epgKeepDays", v)
    var epgDescriptions get() = bool("epgDescriptions", true); set(v) = put("epgDescriptions", v)
    var epgIntervalHours get() = int("epgIntervalHours", 0); set(v) = put("epgIntervalHours", v)
    var epgOnStart get() = bool("epgOnStart", true); set(v) = put("epgOnStart", v)
    var epgOnPlaylist get() = bool("epgOnPlaylist", true); set(v) = put("epgOnPlaylist", v)
    var epgLastUpdate get() = sp.getLong("epgLastUpdate", 0L); set(v) = put("epgLastUpdate", v)
    var epgLastStatus get() = str("epgLastStatus", "—"); set(v) = put("epgLastStatus", v)

    // Appearance
    var theme get() = str("theme", "Dark"); set(v) = put("theme", v)
    var accent get() = str("accent", "Lagoon"); set(v) = put("accent", v)
    var fontSize get() = str("fontSize", "Medium"); set(v) = put("fontSize", v)
    var guideRowSize get() = str("guideRowSize", "Medium"); set(v) = put("guideRowSize", v)
    var guideHours get() = int("guideHours", 3); set(v) = put("guideHours", v)
    var guideChannelNumbers get() = bool("guideChannelNumbers", true); set(v) = put("guideChannelNumbers", v)
    var guideLogos get() = bool("guideLogos", true); set(v) = put("guideLogos", v)
    var guideProgress get() = bool("guideProgress", true); set(v) = put("guideProgress", v)
    var guideStartCollapsed get() = bool("guideStartCollapsed", true); set(v) = put("guideStartCollapsed", v)
    var playerClock get() = bool("playerClock", true); set(v) = put("playerClock", v)
    var playerInfoSeconds get() = int("playerInfoSeconds", 5); set(v) = put("playerInfoSeconds", v)
    var playerNextProgram get() = bool("playerNextProgram", true); set(v) = put("playerNextProgram", v)
    var groupsShowCounts get() = bool("groupsShowCounts", true); set(v) = put("groupsShowCounts", v)
    var groupsSplitSemicolon get() = bool("groupsSplitSemicolon", true); set(v) = put("groupsSplitSemicolon", v)
    var groupsHideEmpty get() = bool("groupsHideEmpty", true); set(v) = put("groupsHideEmpty", v)
    var sortMode get() = str("sortMode", "Playlist"); set(v) = put("sortMode", v)
    var timeFormat get() = str("timeFormat", "System"); set(v) = put("timeFormat", v)

    // Playback
    var buffer get() = str("buffer", "Medium"); set(v) = put("buffer", v)
    var audioDecoder get() = str("audioDecoder", "Hardware"); set(v) = put("audioDecoder", v)
    var videoDecoder get() = str("videoDecoder", "Hardware"); set(v) = put("videoDecoder", v)
    var afr get() = str("afr", "Off"); set(v) = put("afr", v)
    var surroundDefault get() = bool("surroundDefault", false); set(v) = put("surroundDefault", v)
    var passthrough get() = bool("passthrough", false); set(v) = put("passthrough", v)
    var tunneled get() = bool("tunneled", false); set(v) = put("tunneled", v)
    var aspect get() = str("aspect", "Fit"); set(v) = put("aspect", v)
    var autoRetry get() = bool("autoRetry", true); set(v) = put("autoRetry", v)
    var preferAudioLang get() = str("preferAudioLang", "eng"); set(v) = put("preferAudioLang", v)
    var preferSubLang get() = str("preferSubLang", "Off"); set(v) = put("preferSubLang", v)
    var sleepMinutes get() = int("sleepMinutes", 0); set(v) = put("sleepMinutes", v)

    // Remote
    var guideOkAction get() = str("guideOkAction", "Preview, then fullscreen"); set(v) = put("guideOkAction", v)
    var guideLongOk get() = str("guideLongOk", "Channel menu"); set(v) = put("guideLongOk", v)
    var playerUpDown get() = str("playerUpDown", "Switch channel"); set(v) = put("playerUpDown", v)
    var playerLeft get() = str("playerLeft", "Channel list"); set(v) = put("playerLeft", v)
    var playerRight get() = str("playerRight", "Program info"); set(v) = put("playerRight", v)
    var playerOk get() = str("playerOk", "Channel list"); set(v) = put("playerOk", v)
    var channelWrap get() = bool("channelWrap", true); set(v) = put("channelWrap", v)
    var seekUseRwFf get() = bool("seekUseRwFf", true); set(v) = put("seekUseRwFf", v)
    var seekRwLive get() = bool("seekRwLive", true); set(v) = put("seekRwLive", v)
    var seekLeftRight get() = bool("seekLeftRight", true); set(v) = put("seekLeftRight", v)
    var seekLeftLive get() = bool("seekLeftLive", false); set(v) = put("seekLeftLive", v)
    var seekUpDown get() = bool("seekUpDown", false); set(v) = put("seekUpDown", v)
    var seekDownLive get() = bool("seekDownLive", false); set(v) = put("seekDownLive", v)
    var seekStepSec get() = int("seekStepSec", 10); set(v) = put("seekStepSec", v)
    var numberZap get() = bool("numberZap", true); set(v) = put("numberZap", v)

    // Parental
    var pinEnabled get() = bool("pinEnabled", false); set(v) = put("pinEnabled", v)
    var pin get() = str("pin", ""); set(v) = put("pin", v)
    var pinMethod get() = str("pinMethod", "Picker"); set(v) = put("pinMethod", v)
    var pinRelock get() = str("pinRelock", "Always require"); set(v) = put("pinRelock", v)
    var pinFor: Set<String> get() = set("pinFor"); set(v) = put("pinFor", v)

    // Other
    var searchChannels get() = bool("searchChannels", true); set(v) = put("searchChannels", v)
    var searchPrograms get() = bool("searchPrograms", true); set(v) = put("searchPrograms", v)
    var searchHistory get() = bool("searchHistory", true); set(v) = put("searchHistory", v)
    var remindLeadMinutes get() = int("remindLeadMinutes", 2); set(v) = put("remindLeadMinutes", v)
    var remindAutoSwitch get() = bool("remindAutoSwitch", false); set(v) = put("remindAutoSwitch", v)
    var recPaddingBefore get() = int("recPaddingBefore", 0); set(v) = put("recPaddingBefore", v)
    var recPaddingAfter get() = int("recPaddingAfter", 2); set(v) = put("recPaddingAfter", v)
    var recFolder get() = str("recFolder", "App storage"); set(v) = put("recFolder", v)
    var recKeepDays get() = int("recKeepDays", 0); set(v) = put("recKeepDays", v)

    // Collections
    var favorites: Set<String> get() = set("favorites"); set(v) = put("favorites", v)
    var hiddenChannels: Set<String> get() = set("hiddenChannels"); set(v) = put("hiddenChannels", v)
    var hiddenGroups: Set<String> get() = set("hiddenGroups"); set(v) = put("hiddenGroups", v)
    var lockedGroups: Set<String> get() = set("lockedGroups"); set(v) = put("lockedGroups", v)
    var lockedChannels: Set<String> get() = set("lockedChannels"); set(v) = put("lockedChannels", v)
    var groupOrder: List<String> get() = str("groupOrder", "").split("\u001f").filter { it.isNotEmpty() }; set(v) = put("groupOrder", v.joinToString("\u001f"))
    var groupRenames: Map<String, String>
        get() = JSONObject(str("groupRenames", "{}")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        set(v) = put("groupRenames", JSONObject(v).toString())
    var recents: List<String> get() = str("recents", "").split("\u001f").filter { it.isNotEmpty() }; set(v) = put("recents", v.joinToString("\u001f"))
    var searchTerms: List<String> get() = str("searchTerms", "").split("\u001f").filter { it.isNotEmpty() }; set(v) = put("searchTerms", v.joinToString("\u001f"))

    // Playlists: [{id,name,url,epg}]. id "builtin" is the bundled catalog.
    var playlistsJson: String get() = str("playlists", "[]"); set(v) = put("playlists", v)
    fun playlists(): List<PlaylistDef> {
        val arr = JSONArray(playlistsJson)
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            PlaylistDef(it.getString("id"), it.getString("name"), it.optString("url"), it.optString("epg"), it.optBoolean("enabled", true))
        }.toMutableList()
        if (list.none { it.id == "builtin" }) list.add(0, PlaylistDef("builtin", "Live TV", "", "", true))
        return list
    }
    fun savePlaylists(l: List<PlaylistDef>) {
        playlistsJson = JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("epg", it.epg).put("enabled", it.enabled) }).toString()
    }
    var epgSourcesJson: String get() = str("epgSources", "[]"); set(v) = put("epgSources", v)
    fun epgSources(): List<String> = JSONArray(epgSourcesJson).let { a -> (0 until a.length()).map { a.getString(it) } }
    fun saveEpgSources(l: List<String>) { epgSourcesJson = JSONArray(l).toString() }
}

data class PlaylistDef(val id: String, val name: String, val url: String, val epg: String, val enabled: Boolean)
