package app.musike.android

import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Suggestions made on the phone from what you play, like, download and keep: no account, nothing sent but the
 * YouTube Music lookups. A song's related songs come saved with its download, else from YouTube Music's radio for it.
 */
object Suggest {
    private fun key(artist: String) = artist.substringBefore(",").trim().lowercase()

    private fun like(t: Track): List<Track> = Downloads.related(t.id) ?: runCatching { YouTube.radio(t.id) }.getOrDefault(emptyList())

    /**
     * Your taste: artists weigh by where they show up (favorites most, then downloads, playlists, history, the phone's
     * own music); [ranked] is your songs, the ones you'd most miss first, one per artist.
     */
    private class Taste(lib: LibraryData, downloads: List<Track>, phone: List<Track>, use: Set<Source> = Source.entries.toSet()) {
        val weight = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val mine: List<Track>
        val ranked: List<Track>
        init {
            fun from(s: Source, l: List<Track>) = if (s in use) l else emptyList()
            val favorites = from(Source.Favorites, lib.favorites)
            val inPlaylists = from(Source.Playlists, lib.playlists.flatMap { it.tracks })
            val history = from(Source.History, lib.history)
            val downloads = from(Source.Downloads, downloads)
            val phone = from(Source.Phone, phone)
            listOf(favorites to 3.0, downloads to 2.0, inPlaylists to 1.5, history to 1.0, phone to 1.0).forEach { (ts, w) ->
                ts.forEach { if (it.artist.isNotBlank()) { weight.merge(key(it.artist), w, Double::plus); names[key(it.artist)] = it.artist.substringBefore(",").trim() } }
            }
            mine = (favorites + downloads + inPlaylists + history).distinctBy { it.id }
            val favs = favorites.map { it.id }.toSet()
            ranked = mine.sortedByDescending { (if (it.id in favs) 3.0 else 0.0) + (weight[key(it.artist)] ?: 0.0) }.distinctBy { key(it.artist) }
        }
    }

    // ---- Home's "because you listened to…": thought over now and then, not redone at every song

    private const val HOME_EVERY = 6 * 3600_000L
    /** Fewer songs heard than this, and there's no taste to go on yet. */
    private const val HOME_MIN_HEARD = 5
    private val _home = MutableStateFlow(loadHome())
    val home = _home.asStateFlow()
    private val homeLock = Mutex()

    /**
     * Thinks Home's suggestions over again when they're 6 hours old (or there are none yet, once you've heard a few
     * songs). Seeds are drawn from your taste, favoured artists likelier, so each round brings other rows; each row
     * is the seed's related songs not heard yet, artists you like first.
     */
    suspend fun refreshHome(lib: LibraryData, downloads: List<Track>, phone: List<Track>) = homeLock.withLock {
        if (_home.value.isNotEmpty() && System.currentTimeMillis() - Musike.prefs.getLong("homeAt", 0) < HOME_EVERY) return@withLock
        if ((lib.history + lib.favorites + downloads).distinctBy { it.id }.size < HOME_MIN_HEARD) return@withLock
        val taste = Taste(lib, downloads, phone)
        val heard = taste.mine.map { it.id }.toSet()
        // a weighted draw from the top ten: the favourite artists most of the time, the others now and then
        val pool = taste.ranked.take(10).toMutableList()
        val seeds = List(minOf(3, pool.size)) {
            val w = pool.map { (taste.weight[key(it.artist)] ?: 0.0) + 1.0 }
            var r = Math.random() * w.sum()
            pool.removeAt(w.indexOfFirst { r -= it; r <= 0 }.coerceAtLeast(0))
        }
        val rows = coroutineScope {
            seeds.map { seed -> async(Dispatchers.IO) {
                seed to like(seed).filter { it.id !in heard }.sortedByDescending { taste.weight[key(it.artist)] ?: 0.0 }.take(15)
            } }.awaitAll()
        }.filter { it.second.isNotEmpty() }
        if (rows.isEmpty()) return@withLock // offline: keep the last ones
        _home.value = rows
        Musike.prefs.edit().putLong("homeAt", System.currentTimeMillis()).putString("home", JSONArray().apply {
            rows.forEach { (s, ts) -> put(JSONObject().put("seed", s.toJson()).put("tracks", JSONArray().apply { ts.forEach { put(it.toJson()) } })) }
        }.toString()).apply()
    }

    private fun loadHome(): List<Pair<Track, List<Track>>> = runCatching {
        val a = JSONArray(Musike.prefs.getString("home", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val ts = o.getJSONArray("tracks")
            Track.from(o.optJSONObject("seed"))?.let { it to (0 until ts.length()).mapNotNull { j -> Track.from(ts.optJSONObject(j)) } }
        }
    }.getOrDefault(emptyList())

    /** What a smart playlist can be made from. */
    enum class Source { Favorites, Downloads, Playlists, History, Phone }
    /** Familiar: more from your artists. Mixed: them and ones like them. Discover: only artists new to you. */
    enum class Style { Familiar, Mixed, Discover }

    /** Your artists from these sources, the ones that weigh most first. */
    fun artists(lib: LibraryData, downloads: List<Track>, phone: List<Track>, use: Set<Source>): List<String> =
        Taste(lib, downloads, phone, use).let { t -> t.weight.entries.sortedByDescending { it.value }.map { t.names[it.key]!! } }

    /**
     * A playlist from your taste in [use] (focused on [only] artists, when picked): the songs related to several of
     * yours rank first, and by [style], artists you know weigh more, as much, or not at all. Nothing you already have.
     * Empty when there's nothing to go on.
     */
    suspend fun mix(lib: LibraryData, downloads: List<Track>, phone: List<Track>, use: Set<Source>, only: Set<String>, style: Style, size: Int): List<Track> = coroutineScope {
        val taste = Taste(lib, downloads, phone, use)
        val weight = taste.weight
        if (weight.isEmpty()) return@coroutineScope emptyList()
        val focus = only.map(::key).toSet()
        // never a song you have, whatever the mix is made from
        val all = Taste(lib, downloads, phone)
        val have = all.mine.map { it.id }.toSet() + phone.map { "${key(it.artist)}|${it.title.lowercase()}" }
        // seeds: your songs that weigh most, one per artist (up to 3 each for picked artists)
        val seeds = if (focus.isEmpty()) taste.ranked.take(8)
            else taste.mine.filter { key(it.artist) in focus }.groupBy { key(it.artist) }.values.flatMap { it.take(3) }.take(12)
        // artists to take songs from straight: the picked ones, else the top ones with no song to seed from (only on
        // the phone, or past the seeds). discovering has no use for them.
        val direct = when {
            style == Style.Discover -> emptyList()
            focus.isNotEmpty() -> focus.toList()
            else -> weight.entries.sortedByDescending { it.value }.take(if (style == Style.Familiar) 6 else 4).map { it.key }
                .filter { a -> style == Style.Familiar || seeds.none { key(it.artist) == a } }
        }
        val lists = (seeds.map { s -> async(Dispatchers.IO) { like(s) } } +
            direct.map { a -> async(Dispatchers.IO) { runCatching { YouTube.search(taste.names[a] ?: a, "song").take(15) }.getOrDefault(emptyList()) } }).awaitAll()
        val score = HashMap<String, Double>()
        val byId = HashMap<String, Track>()
        for (l in lists) l.forEachIndexed { i, t ->
            if (t.id in have || "${key(t.artist)}|${t.title.lowercase()}" in have) return@forEachIndexed
            if (style == Style.Discover && key(t.artist) in all.weight) return@forEachIndexed
            byId[t.id] = t
            // each list that has it counts, near its top counts more
            score.merge(t.id, 1.0 + (1.0 - i.toDouble() / l.size), Double::plus)
        }
        val known = when (style) { Style.Familiar -> 2.0; Style.Mixed -> 1.0; Style.Discover -> 0.0 }
        val cap = when (style) { Style.Familiar -> 5; Style.Mixed -> 3; Style.Discover -> 2 }
        val perArtist = HashMap<String, Int>()
        byId.values.sortedByDescending { (score[it.id] ?: 0.0) + known * ln(1 + (weight[key(it.artist)] ?: 0.0)) + if (key(it.artist) in focus) 2.0 else 0.0 }
            .filter { perArtist.merge(key(it.artist), 1, Int::plus)!! <= cap }
            .take(size)
    }

    /** Names a mix by the artists it's made from: "Mix · Artist, Artist". */
    fun name(tracks: List<Track>) = "${str.mix} · " + tracks.map { it.artist.substringBefore(",").trim() }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2).joinToString(", ") { it.key }
}
