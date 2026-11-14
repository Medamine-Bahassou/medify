package app.musike.android

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Same shape as server/src/types.ts, so library.json and backups move between every Musike. */
data class Track(val id: String, val title: String, val artist: String, val album: String?, val duration: Int, val thumb: String, val art: String, val kind: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("artist", artist).put("duration", duration).put("thumb", thumb).put("art", art).put("kind", kind)
        .apply { if (album != null) put("album", album) }

    companion object {
        /** Keeps only valid fields: the input may come from an old version or someone else's backup. */
        fun from(o: JSONObject?): Track? {
            o ?: return null
            val id = o.optString("id").take(11).takeIf { VIDEO_ID.matches(it) } ?: return null
            fun str(k: String, max: Int = 300) = (o.opt(k) as? String)?.take(max) ?: ""
            fun url(k: String) = str(k, 500).takeIf { it.startsWith("https://") } ?: ""
            return Track(id, str("title").ifEmpty { "Untitled" }, str("artist"), str("album").ifEmpty { null },
                (o.opt("duration") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { maxOf(0, Math.round(it).toInt()) } ?: 0,
                url("thumb"), url("art"), if (o.optString("kind") == "video") "video" else "song")
        }
    }
}

data class Playlist(val id: String, val name: String, val createdAt: Long, val updatedAt: Long, val tracks: List<Track>)
data class LibraryData(val favorites: List<Track> = emptyList(), val playlists: List<Playlist> = emptyList(), val history: List<Track> = emptyList())

/** App-wide setup and settings, shared by the screens and the playback service (same process). */
object Musike {
    lateinit var prefs: SharedPreferences
    lateinit var app: Context
    var lang by mutableStateOf("en")
    var theme by mutableStateOf("system")
    /** A colour like "#ff4757", or "dynamic" for the wallpaper's colours (Android 12+). */
    var accent by mutableStateOf("#ff4757")
    var autoRadio by mutableStateOf(true)
    /** Liquid glass on the floating chrome; off draws plain surfaces (lighter on old phones). */
    var glass by mutableStateOf(true)
    /**
     * Glass needs Android 12 to blur (before, it's an opaque surface that only costs frames) and memory to spare: a
     * low-memory phone never gets it, one with under 4 GB starts with it off.
     */
    var glassCapable = false
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("musike", Context.MODE_PRIVATE)
        val phone = Locale.getDefault()
        // the phone's language when the app has it
        lang = prefs.getString("lang", null) ?: when (phone.language) { "pt" -> "pt-BR"; "fr" -> "fr"; "ar" -> "ar"; else -> "en" }
        theme = prefs.getString("theme", "system")!!
        accent = prefs.getString("accent", null) ?: "#ff4757"
        autoRadio = prefs.getBoolean("autoRadio", true)
        val am = app.getSystemService(ActivityManager::class.java)
        glassCapable = Build.VERSION.SDK_INT >= 31 && !am.isLowRamDevice
        val roomy = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem >= 3_500_000_000L // "4 GB" phones report a bit less
        glass = glassCapable && prefs.getBoolean("glass", roomy)
        YouTube.hl = youtubeLang(lang)
        YouTube.gl = phone.country.takeIf { Regex("^[A-Z]{2}$").matches(it) } ?: "US"
        Library.load(File(ctx.filesDir, "library.json"))
        Downloads.init(ctx)
    }

    fun set(key: String, value: Any) {
        when (key) {
            "lang" -> { lang = value as String; YouTube.hl = youtubeLang(lang) }
            "theme" -> theme = value as String
            "accent" -> accent = value as String
            "autoRadio" -> autoRadio = value as Boolean
            "glass" -> glass = value as Boolean
        }
        prefs.edit().apply { if (value is Boolean) putBoolean(key, value) else putString(key, value.toString()) }.apply()
    }

    val dynamicAvailable get() = Build.VERSION.SDK_INT >= 31

    /** YouTube words genre names, artist bios and album lines in this language. */
    private fun youtubeLang(lang: String) = lang.substringBefore('-')
}

/** Favorites, playlists and history: the port of server/src/library.ts, kept in the same library.json the WebView app wrote. */
object Library {
    private const val HISTORY_LIMIT = 60
    private val _state = MutableStateFlow(LibraryData())
    val state = _state.asStateFlow()
    private lateinit var file: File
    private val tmp get() = File(file.parentFile, "library.tmp.json")
    private val writer = Executors.newSingleThreadExecutor()

    fun load(f: File) {
        file = f
        val json = runCatching { file.readText() }.getOrNull() ?: runCatching { tmp.readText() }.getOrNull()
        _state.value = json?.let { runCatching { clean(JSONObject(it)) }.getOrNull() } ?: LibraryData()
    }

    /** Applies a change and saves it. Each save goes to a temp file first, so a crash mid-write can't leave half a library. */
    private fun change(fn: (LibraryData) -> LibraryData) {
        _state.update(fn)
        val snapshot = toJson(_state.value).toString(2)
        writer.execute { tmp.writeText(snapshot); file.delete(); tmp.renameTo(file) }
    }

    fun isFavorite(id: String) = state.value.favorites.any { it.id == id }
    fun toggleFavorite(t: Track) = change { l -> l.copy(favorites = if (l.favorites.any { it.id == t.id }) l.favorites.filter { it.id != t.id } else listOf(t) + l.favorites) }
    /** Adds the songs to favorites (on) or takes them out. */
    fun setFavorites(tracks: List<Track>, on: Boolean) = change { l ->
        val ids = tracks.map { it.id }.toSet()
        l.copy(favorites = if (on) tracks.filter { t -> l.favorites.none { it.id == t.id } }.distinctBy { it.id } + l.favorites else l.favorites.filter { it.id !in ids })
    }
    fun removeFromHistory(id: String) = removeFromHistory(setOf(id))
    fun removeFromHistory(ids: Set<String>) = change { l -> l.copy(history = l.history.filter { it.id !in ids }) }
    fun pushHistory(t: Track) = change { l -> l.copy(history = (listOf(t) + l.history.filter { it.id != t.id }).take(HISTORY_LIMIT)) }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val now = System.currentTimeMillis()
        val p = Playlist(UUID.randomUUID().toString(), name.trim().take(80), now, now, tracks.distinctBy { it.id })
        change { l -> l.copy(playlists = listOf(p) + l.playlists) }
        return p
    }

    private fun editPlaylist(id: String, fn: (Playlist) -> Playlist) = change { l -> l.copy(playlists = l.playlists.map { if (it.id == id) fn(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().take(80)) }
    fun deletePlaylist(id: String) = change { l -> l.copy(playlists = l.playlists.filter { it.id != id }) }
    fun addToPlaylist(id: String, tracks: List<Track>) = editPlaylist(id) { p -> p.copy(tracks = p.tracks + tracks.filter { t -> p.tracks.none { it.id == t.id } }.distinctBy { it.id }) }
    fun removeFromPlaylist(id: String, trackId: String) = removeFromPlaylist(id, setOf(trackId))
    fun removeFromPlaylist(id: String, trackIds: Set<String>) = editPlaylist(id) { p -> p.copy(tracks = p.tracks.filter { it.id !in trackIds }) }

    // ---- backup: the same file format Musike exports (web/src/lib/backup.ts), so either app reads the other's

    fun backup(version: String): String = JSONObject().put("format", "musike-backup").put("version", 1).put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())).put("app", "Medify $version")
        .put("library", toJson(state.value))
        .put("settings", JSONObject().put("lang", Musike.lang).put("autoRadio", Musike.autoRadio).apply { if (Musike.theme != "system") put("theme", Musike.theme) })
        .toString(2)

    /** Reads a backup file; throws with a reason the user can act on. */
    fun readBackup(text: String): LibraryData {
        val b = runCatching { JSONObject(text) }.getOrNull()
        if (b?.optString("format") != "musike-backup") throw IllegalArgumentException(str.notBackup)
        if (b.optInt("version", 99) > 1) throw IllegalArgumentException(str.newerBackup)
        return clean(b.optJSONObject("library") ?: JSONObject())
    }

    /** "merge" adds what's missing and never deletes (playlists match by id, then name); "replace" swaps the whole library. */
    fun import(incoming: LibraryData, replace: Boolean) = change { lib ->
        if (replace) return@change incoming
        fun union(mine: List<Track>, theirs: List<Track>) = mine + theirs.filter { t -> mine.none { it.id == t.id } }
        var playlists = lib.playlists
        for (p in incoming.playlists) {
            val match = playlists.firstOrNull { it.id == p.id } ?: playlists.firstOrNull { it.name.lowercase() == p.name.lowercase() }
            playlists = if (match == null) playlists + p
            else playlists.map { if (it === match) it.copy(tracks = union(it.tracks, p.tracks).take(5000), updatedAt = System.currentTimeMillis()) else it }
        }
        LibraryData(union(lib.favorites, incoming.favorites), playlists, union(lib.history, incoming.history).take(HISTORY_LIMIT))
    }

    private fun tracks(a: JSONArray?, limit: Int = 5000) = a?.let { (0 until minOf(it.length(), limit * 2)).mapNotNull { i -> Track.from(it.optJSONObject(i)) }.distinctBy { t -> t.id }.take(limit) } ?: emptyList()

    private fun clean(o: JSONObject): LibraryData {
        val lists = o.optJSONArray("playlists")
        val playlists = (0 until minOf(lists?.length() ?: 0, 1000)).mapNotNull { i ->
            val p = lists!!.optJSONObject(i) ?: return@mapNotNull null
            val name = p.optString("name").take(80).trim().ifEmpty { return@mapNotNull null }
            val now = System.currentTimeMillis()
            val id = p.optString("id").take(64).takeIf { Regex("^[\\w-]{8,64}$").matches(it) } ?: UUID.randomUUID().toString()
            Playlist(id, name, p.optLong("createdAt", now), p.optLong("updatedAt", now), tracks(p.optJSONArray("tracks")))
        }
        return LibraryData(tracks(o.optJSONArray("favorites")), playlists, tracks(o.optJSONArray("history"), HISTORY_LIMIT))
    }

    private fun toJson(l: LibraryData): JSONObject {
        fun arr(ts: List<Track>) = JSONArray().apply { ts.forEach { put(it.toJson()) } }
        return JSONObject().put("favorites", arr(l.favorites)).put("history", arr(l.history)).put("playlists", JSONArray().apply {
            l.playlists.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("tracks", arr(it.tracks))) }
        })
    }
}
