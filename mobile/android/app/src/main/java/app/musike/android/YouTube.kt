package app.musike.android

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// YouTube Music's own API (InnerTube), read the way server/src/search.ts and browse.ts read it through youtubei.js.
// Everything here blocks: call it off the main thread.

data class AlbumCard(val id: String, val title: String, val artist: String, val year: String, val thumb: String, val art: String)
data class ArtistCard(val id: String, val name: String, val info: String, val thumb: String)
data class Genre(val id: String, val name: String, val color: Long = 0)
data class GenreArtist(val id: String, val name: String, val thumb: String, val art: String, val hits: List<String>)
data class GenrePage(val id: String, val name: String, val artists: List<GenreArtist>, val hasMoreArtists: Boolean, val tracks: List<Track>)
data class ArtistPage(val id: String, val name: String, val description: String, val banner: String, val tracks: List<Track>, val albums: List<AlbumCard>, val singles: List<AlbumCard>)
data class AlbumPage(val id: String, val title: String, val artist: String, val artistId: String?, val kind: String, val info: String, val art: String, val tracks: List<Track>)
data class Resolved(val url: String, val expiresAt: Long, val title: String, val author: String, val duration: Int)

val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
val ALBUM_ID = Regex("^MPRE[\\w-]{6,60}$")
val ARTIST_ID = Regex("^UC[\\w-]{22}$")

object YouTube {
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json".toMediaType()
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    // the player answers this browser with URLs the phone can read to the end (same as ios/App/App/MusikeYouTube.swift)
    const val STREAM_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    /** YouTube's language ("en", "pt") and the phone's region, set from the settings. */
    @Volatile var hl = "en"
    @Volatile var gl = "US"

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return JSONObject(r.body!!.string())
        }
    }

    private fun music(endpoint: String, body: JSONObject): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20251110.01.00").put("hl", hl).put("gl", gl)))
        return post("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", body,
            mapOf("User-Agent" to WEB_UA, "Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/"))
    }

    private fun browse(id: String, params: String? = null) = music("browse", JSONObject().put("browseId", id).apply { if (params != null) put("params", params) })

    // time-boxed memo, per language (YouTube words genre names and bios in it): going back to a page should be
    // instant, and these change slowly. failures aren't kept, so the next visit tries again.
    private val memo = ConcurrentHashMap<String, Pair<Long, Any>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, minutes: Int, load: () -> T): T {
        val k = "$hl|$key"
        memo[k]?.let { (at, v) -> if (System.currentTimeMillis() - at < minutes * 60_000L) return v as T }
        if (memo.size > 200) memo.clear()
        return load().also { memo[k] = System.currentTimeMillis() to it }
    }

    // ---- search

    private val FILTER = mapOf("song" to "EgWKAQIIAWoKEAkQBRAKEAMQBA==", "video" to "EgWKAQIQAWoKEAkQChAFEAMQBA==", "album" to "EgWKAQIYAWoKEAkQAxAEEAoQBQ==",
        "artist" to "EgWKAQIgAWoKEAkQChAFEAMQBA==")

    /** YouTube Music first, then plain YouTube's search (another endpoint, videos only): some networks refuse one of them. */
    fun search(query: String, type: String): List<Track> {
        val failures = mutableListOf<String>()
        try {
            val tracks = distinct(all(music("search", JSONObject().put("query", query).put("params", FILTER[type])), "musicResponsiveListItemRenderer").mapNotNull { listItem(it, type) })
            if (tracks.isNotEmpty()) return tracks
            failures += "YouTube Music: no results"
        } catch (e: Exception) { failures += "YouTube Music: ${e.message}" }
        try {
            val body = JSONObject().put("query", query).put("params", "EgIQAQ%3D%3D")
                .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", "2.20251110.01.00").put("hl", hl).put("gl", gl)))
            val res = post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body, mapOf("User-Agent" to WEB_UA, "Origin" to "https://www.youtube.com"))
            val tracks = all(res, "videoRenderer").mapNotNull { v ->
                val id = v.optString("videoId").takeIf { VIDEO_ID.matches(it) } ?: return@mapNotNull null
                Track(id, text(v.opt("title")), text(v.opt("ownerText")), null, seconds(text(v.opt("lengthText"))), videoThumb(id), videoArt(id), "video")
            }
            if (tracks.isNotEmpty()) return distinct(tracks)
            failures += "YouTube: no results"
        } catch (e: Exception) { failures += "YouTube: ${e.message}" }
        throw IOException(failures.joinToString("; "))
    }

    fun suggestions(query: String): List<String> =
        all(music("music/get_search_suggestions", JSONObject().put("input", query)), "searchSuggestionRenderer").map { text(it.opt("suggestion")) }.filter { it.isNotBlank() }.take(8)

    fun searchAlbums(query: String): List<AlbumCard> =
        all(music("search", JSONObject().put("query", query).put("params", FILTER["album"])), "musicResponsiveListItemRenderer").mapNotNull { r ->
            val id = r.at("navigationEndpoint", "browseEndpoint", "browseId") as? String ?: return@mapNotNull null
            if (!ALBUM_ID.matches(id)) return@mapNotNull null
            val runs = columnRuns(r, 1)
            val (thumb, art) = images(r)
            AlbumCard(id, text(columnText(r, 0)), artistsOf(runs).joinToString(", ") { it.second }, YEAR.find(runsText(runs))?.value ?: "", thumb, art)
        }

    fun searchArtists(query: String): List<ArtistCard> =
        all(music("search", JSONObject().put("query", query).put("params", FILTER["artist"])), "musicResponsiveListItemRenderer").mapNotNull { r ->
            val id = (r.at("navigationEndpoint", "browseEndpoint", "browseId") as? String)?.takeIf { ARTIST_ID.matches(it) } ?: return@mapNotNull null
            ArtistCard(id, text(columnText(r, 0)), columnText(r, 1), images(r).first)
        }.distinctBy { it.id }

    /** YouTube Music's radio for a track: related songs to keep playing. */
    fun radio(id: String): List<Track> = cached("radio|$id", 30) {
        all(music("next", JSONObject().put("videoId", id).put("playlistId", "RDAMVM$id").put("isAudioOnly", true)), "playlistPanelVideoRenderer").mapNotNull { p ->
            val vid = p.optString("videoId").takeIf { VIDEO_ID.matches(it) && it != id } ?: return@mapNotNull null
            val runs = p.optJSONObject("longBylineText")?.optJSONArray("runs")
            val url = (p.at("thumbnail", "thumbnails") as? JSONArray)?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            track(vid, text(p.opt("title")), runs, seconds(text(p.opt("lengthText"))), url, "song")
        }.let(::distinct)
    }

    // ---- browse

    fun genres(): List<Genre> = cached("genres", 360) {
        val grids = all(browse("FEmusic_moods_and_genres"), "gridRenderer")
        // the page has "moods" then "genres": genres first (their title matched in any language, else the last grid), moods after
        val isGenres = { g: JSONObject -> Regex("g[eéê]ne?r", RegexOption.IGNORE_CASE).containsMatchIn(text(g.at("header", "gridHeaderRenderer", "title"))) }
        val ordered = if (grids.any(isGenres)) grids.filter(isGenres) + grids.filterNot(isGenres) else grids.reversed()
        ordered.flatMap { all(it, "musicNavigationButtonRenderer") }.mapNotNull { b ->
            val id = b.at("clickCommand", "browseEndpoint", "params") as? String ?: return@mapNotNull null
            // the colour YouTube Music marks the genre with (ARGB)
            Genre(id, text(b.opt("buttonText")), (b.at("solid", "leftStripeColor") as? Number)?.toLong() ?: 0).takeIf { it.name.isNotBlank() }
        }.distinctBy { it.id }
    }

    private class GenreState(val page: GenrePage, val pool: MutableList<GenreArtist>, val queue: MutableList<String>)
    private const val ARTISTS_PAGE = 40

    private fun genreState(id: String): GenreState = cached("genre|$id", 30) {
        val shelves = sectionContents(browse("FEmusic_moods_and_genres_category", id))
        val items = shelves.flatMap(::itemsOf)
        val songs = items.filter { it.has("flexColumns") }
        val albums = items.filter { !it.has("flexColumns") && ALBUM_ID.matches(browseIdOf(it)) }
        // one playlist from each shelf in turn (so "Decades" mixes decades), editorial (RDCLAK) before community ones
        val perShelf = shelves.map { s -> itemsOf(s).map(::browseIdOf).filter { it.startsWith("VL") }.map { it.removePrefix("VL") } }
        val playlists = (roundRobin(perShelf.map { l -> l.filter { "RDCLAK" in it } }) + roundRobin(perShelf.map { l -> l.filterNot { "RDCLAK" in it } })).distinct()
        val first = if (songs.size >= 10) 2 else 4
        val fromPlaylists = playlists.take(first).flatMap { playlistItems(it, 60) }
        val tracks = uniqueTracks((songs + fromPlaylists).mapNotNull { listItem(it, "song") }).take(150)
        if (tracks.isEmpty() && albums.isEmpty()) throw IOException("This genre is empty.")
        val pool = rankArtists(listOf(Triple(songs, 2, true), Triple(albums, 2, false), Triple(fromPlaylists, 1, true)), emptySet()).toMutableList()
        val rest = playlists.drop(first)
        val queue = (if (rest.any { "RDCLAK" in it }) rest.filter { "RDCLAK" in it } else rest).toMutableList()
        val name = runCatching { genres().firstOrNull { it.id == id }?.name }.getOrNull() ?: ""
        GenreState(GenrePage(id, name, pool.take(ARTISTS_PAGE), pool.size > ARTISTS_PAGE || queue.isNotEmpty(), tracks), pool, queue)
    }

    fun genre(id: String): GenrePage = genreState(id).page

    /** The next page of a genre's artists, reading more of its playlists when the ones found so far run out. */
    fun genreArtists(id: String, offset: Int): Pair<List<GenreArtist>, Boolean> {
        val s = genreState(id)
        synchronized(s) {
            var round = 0
            while (round++ < 5 && s.pool.size < offset + ARTISTS_PAGE && s.queue.isNotEmpty()) {
                val batch = s.queue.take(4).also { repeat(it.size) { s.queue.removeAt(0) } }
                val known = s.pool.map { it.id }.toSet()
                s.pool += rankArtists(listOf(Triple(batch.flatMap { playlistItems(it, 100) }, 1, true)), known)
                if (s.pool.size >= 600) s.queue.clear()
            }
            return s.pool.drop(offset).take(ARTISTS_PAGE) to (s.pool.size > offset + ARTISTS_PAGE || s.queue.isNotEmpty())
        }
    }

    fun artist(id: String): ArtistPage = cached("artist|$id", 30) {
        val page = browse(id)
        val header = (page.optJSONObject("header")?.let { h -> h.keys().asSequence().firstOrNull()?.let { h.optJSONObject(it) } }) ?: JSONObject()
        val sections = sectionContents(page)
        val shelf = sections.firstOrNull { it.has("contents") && all(it, "musicResponsiveListItemRenderer").isNotEmpty() && !it.has("header") }
        // album shelves are told apart by what they hold, not their (translated) titles: albums first, then singles
        val releases = sections.filter { s -> itemsOf(s).any { ALBUM_ID.matches(browseIdOf(it)) } }
        // the shelf shows 5; its title links to a playlist with the full ranking
        val full = shelf?.let { (it.at("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId") ?: it.at("bottomEndpoint", "browseEndpoint", "browseId")) as? String }
        val items = full?.takeIf { it.startsWith("VL") }?.let { runCatching { playlistItems(it.removePrefix("VL"), 100) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            ?: itemsOf(shelf)
        val albums = releases.getOrNull(0)?.let { s ->
            val shown = itemsOf(s).mapNotNull(::albumCard)
            val more = s.at("header", "musicCarouselShelfBasicHeaderRenderer", "moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint") as? JSONObject
            val all = more?.let { m -> runCatching { all(browse(m.getString("browseId"), m.optString("params").ifEmpty { null }), "musicTwoRowItemRenderer").mapNotNull(::albumCard) }.getOrNull() }
            if (all != null && all.size >= shown.size) all else shown
        } ?: emptyList()
        val name = text(header.opt("title"))
        val tracks = uniqueTracks(items.mapNotNull { listItem(it, "song") }).take(50)
        if (name.isEmpty() && tracks.isEmpty()) throw IOException("Artist not found.")
        val banner = (header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JSONArray)?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""
        ArtistPage(id, name, text(header.opt("description")), banner.takeIf { it.startsWith("https://") } ?: "", tracks, albums,
            releases.getOrNull(1)?.let { itemsOf(it).mapNotNull(::albumCard) } ?: emptyList())
    }

    fun album(id: String): AlbumPage = cached("album|$id", 60) {
        val page = browse(id)
        val header = all(page, "musicResponsiveHeaderRenderer").firstOrNull() ?: all(page, "musicDetailHeaderRenderer").firstOrNull() ?: JSONObject()
        val title = text(header.opt("title"))
        val strap = header.optJSONObject("straplineTextOne")
        val artist = text(strap)
        val artistId = strap?.optJSONArray("runs")?.let { runs -> (0 until runs.length()).mapNotNull { runs.optJSONObject(it).at("navigationEndpoint", "browseEndpoint", "browseId") as? String }.firstOrNull { ARTIST_ID.matches(it) } }
        val (_, art) = images(header)
        // the album's songs: the first list shelf (later ones are "other versions")
        val shelf = all(page, "musicShelfRenderer").firstOrNull()
        val tracks = all(shelf, "musicResponsiveListItemRenderer").mapNotNull { r ->
            listItem(r, "song")?.let { t -> t.copy(artist = t.artist.ifEmpty { artist }, album = title, thumb = sized(art, 120), art = art, kind = "song") }
        }
        if (title.isEmpty() && tracks.isEmpty()) throw IOException("Album not found.")
        AlbumPage(id, title, artist, artistId, text(header.opt("subtitle")), text(header.opt("secondSubtitle")), art, tracks)
    }

    private fun playlistItems(id: String, limit: Int): List<JSONObject> =
        runCatching { all(browse("VL$id"), "musicResponsiveListItemRenderer").take(limit) }.getOrDefault(emptyList())

    // ---- streams

    // tried in order: VISIONOS hands out URLs that need no proof-of-origin token, the others are fallbacks YouTube
    // leaves open on some networks
    private val CLIENTS = linkedMapOf("VISIONOS" to ("1.02" to "101"), "ANDROID_VR" to ("1.60.19" to "28"), "IOS" to ("20.10.4" to "5"))
    private val streams = ConcurrentHashMap<String, Resolved>()

    fun forget(id: String) { streams.remove(id) }

    /** A track's audio URL, the job yt-dlp does on a computer. Kept while it's good for another minute. */
    fun resolve(id: String): Resolved {
        streams[id]?.let { if (it.expiresAt - System.currentTimeMillis() > 60_000) return it }
        val visitor = visitorData()
        var reason = ""
        for (name in CLIENTS.keys) {
            val (version, num) = CLIENTS[name] ?: continue
            try {
                val client = JSONObject().put("clientName", name).put("clientVersion", version).put("hl", hl).put("gl", gl)
                    .put("userAgent", "$STREAM_UA,gzip(gfe)").put("platform", "DESKTOP").put("visitorData", visitor).put("originalUrl", "https://www.youtube.com/watch?v=$id")
                val body = JSONObject().put("videoId", id).put("racyCheckOk", true).put("contentCheckOk", true).put("context", JSONObject().put("client", client))
                    .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
                val res = post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&alt=json", body, playerHeaders(version, num))
                val status = res.optJSONObject("playabilityStatus")
                if (status?.optString("status") != "OK") { reason = status?.optString("reason")?.ifEmpty { null } ?: reason; continue }
                val formats = res.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: continue
                // AAC (m4a) first: every phone plays it. highest bitrate within the same kind.
                val best = (0 until formats.length()).map { formats.getJSONObject(it) }
                    .filter { it.optString("mimeType").startsWith("audio/") && it.has("url") }
                    .sortedWith(compareByDescending<JSONObject> { it.optString("mimeType").startsWith("audio/mp4") }.thenByDescending { it.optInt("bitrate") })
                    .firstOrNull() ?: continue
                val url = best.getString("url")
                val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000) ?: (System.currentTimeMillis() + 3 * 3600_000)
                val d = res.optJSONObject("videoDetails") ?: JSONObject()
                return Resolved(url, expire, d.optString("title"), d.optString("author"), d.optString("lengthSeconds").toIntOrNull() ?: 0).also { streams[id] = it }
            } catch (e: Exception) { reason = e.message ?: reason }
        }
        throw IOException(reason.ifEmpty { "No audio for this track." })
    }

    /** A pasted YouTube link's video, named by its player response. */
    fun linked(id: String): Track = resolve(id).let { Track(id, it.title.ifEmpty { "Untitled" }, it.author, null, it.duration, videoThumb(id), videoArt(id), "video") }

    private fun playerHeaders(version: String, num: String) = mapOf("User-Agent" to STREAM_UA, "Origin" to "https://www.youtube.com",
        "X-YouTube-Client-Name" to num, "X-YouTube-Client-Version" to version, "X-Goog-Api-Format-Version" to "2")

    /** The visitor id YouTube handed this install, kept for a day: a player request without one is bot-checked. */
    private fun visitorData(): String {
        val prefs = Musike.prefs
        val saved = prefs.getString("visitorData", "") ?: ""
        if (saved.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("visitorDataAt", 0) < 24 * 3600_000L) return saved
        return try {
            val client = JSONObject().put("clientName", "VISIONOS").put("clientVersion", "1.02").put("hl", hl).put("gl", gl).put("userAgent", "$STREAM_UA,gzip(gfe)").put("platform", "DESKTOP")
            val fresh = post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", JSONObject().put("context", JSONObject().put("client", client)), playerHeaders("1.02", "101"))
                .optJSONObject("responseContext")?.optString("visitorData") ?: ""
            if (fresh.isNotEmpty()) prefs.edit().putString("visitorData", fresh).putLong("visitorDataAt", System.currentTimeMillis()).apply()
            fresh.ifEmpty { saved }
        } catch (e: Exception) { saved }
    }

    // ---- reading InnerTube's JSON, defensively: shapes differ between item types

    /** Every object under `key`, anywhere below `o`, in document order. */
    fun all(o: Any?, key: String, out: MutableList<JSONObject> = mutableListOf()): List<JSONObject> {
        when (o) {
            is JSONObject -> for (k in o.keys()) { val v = o.opt(k); if (k == key && v is JSONObject) out += v else all(v, key, out) }
            is JSONArray -> for (i in 0 until o.length()) all(o.opt(i), key, out)
        }
        return out
    }

    private fun Any?.at(vararg path: Any): Any? = path.fold(this) { o, k ->
        when { o is JSONObject && k is String -> o.opt(k); o is JSONArray && k is Int -> o.opt(k); else -> null }
    }

    /** "runs" joined, or "simpleText". */
    fun text(o: Any?): String = when (o) {
        is String -> o
        is JSONObject -> o.optJSONArray("runs")?.let(::runsText) ?: o.optString("simpleText")
        else -> ""
    }

    private fun runsText(runs: JSONArray?) = runs?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text") ?: "" } } ?: ""

    private fun sectionContents(page: JSONObject): List<JSONObject> =
        all(page, "sectionListRenderer").firstOrNull()?.optJSONArray("contents")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { s -> s.keys().asSequence().firstOrNull()?.let(s::optJSONObject) } } } ?: emptyList()

    /** A shelf's items, unwrapped from their renderer key. */
    private fun itemsOf(shelf: JSONObject?): List<JSONObject> =
        (shelf?.optJSONArray("contents") ?: shelf?.optJSONArray("items"))?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { s -> s.keys().asSequence().firstOrNull()?.let(s::optJSONObject) } } } ?: emptyList()

    private fun browseIdOf(item: JSONObject) = (item.at("navigationEndpoint", "browseEndpoint", "browseId") ?: item.at("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId")) as? String ?: ""

    private fun columnRuns(r: JSONObject, i: Int): JSONArray? = r.at("flexColumns", i, "musicResponsiveListItemFlexColumnRenderer", "text", "runs") as? JSONArray
    private fun columnText(r: JSONObject, i: Int) = runsText(columnRuns(r, i))

    /** (channel id, name) of the artist links in some runs. */
    private fun artistsOf(runs: JSONArray?, artistPagesOnly: Boolean = false): List<Pair<String, String>> = runs?.let { r ->
        (0 until r.length()).mapNotNull { r.optJSONObject(it) }.mapNotNull { run ->
            val b = run.at("navigationEndpoint", "browseEndpoint") as? JSONObject ?: return@mapNotNull null
            val id = b.optString("browseId")
            val type = b.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType") as? String
            if (!ARTIST_ID.matches(id) || (artistPagesOnly && type != null && type != "MUSIC_PAGE_TYPE_ARTIST")) null else id to run.optString("text")
        }
    } ?: emptyList()

    private val TIME = Regex("^\\d+(:\\d{2})+$")
    private val YEAR = Regex("\\b(19|20)\\d\\d\\b")
    private fun seconds(s: String) = s.trim().takeIf { TIME.matches(it) }?.split(":")?.fold(0) { acc, p -> acc * 60 + p.toInt() } ?: 0

    fun sized(url: String?, px: Int): String = when {
        url.isNullOrEmpty() -> ""
        // googleusercontent / ggpht thumbnails encode their size after "="
        Regex("googleusercontent\\.com|ggpht\\.com").containsMatchIn(url) -> url.replace(Regex("=w\\d+-h\\d+[^&]*$"), "=w$px-h$px-l90-rj")
        else -> url
    }

    private fun videoThumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    private fun videoArt(id: String) = "https://i.ytimg.com/vi/$id/hq720.jpg"

    private fun images(item: JSONObject): Pair<String, String> {
        val thumbs = (item.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails") ?: item.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")) as? JSONArray
        val url = thumbs?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
        return sized(url, 240) to sized(url, 544)
    }

    /** A song or video row (search results, playlists, albums, genre shelves). */
    private fun listItem(r: JSONObject, kind: String): Track? {
        val id = (r.at("playlistItemData", "videoId") ?: r.at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId")
            ?: columnRuns(r, 0)?.optJSONObject(0)?.at("navigationEndpoint", "watchEndpoint", "videoId")) as? String ?: return null
        val runs = JSONArray()
        val cols = r.optJSONArray("flexColumns")
        for (c in 1 until (cols?.length() ?: 0)) { columnRuns(r, c)?.let { for (i in 0 until it.length()) runs.put(it.opt(i)) }; runs.put(JSONObject().put("text", " • ")) }
        val fixed = text(r.at("fixedColumns", 0, "musicResponsiveListItemFixedColumnRenderer", "text"))
        val duration = seconds(fixed).takeIf { it > 0 } ?: runsText(runs).split(" • ").map(::seconds).firstOrNull { it > 0 } ?: 0
        val thumbs = r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails") as? JSONArray
        return track(id, columnText(r, 0), runs, duration, thumbs?.let { it.optJSONObject(it.length() - 1)?.optString("url") }, kind)
    }

    private fun track(id: String, title: String, runs: JSONArray?, duration: Int, thumb: String?, kind: String): Track? {
        if (!VIDEO_ID.matches(id)) return null
        // no artist link: the first column's first part names it (a video's channel). album rows leave it empty
        // (their second column is the play count), and album() gives them the album's artist.
        val artist = artistsOf(runs).joinToString(", ") { it.second }.ifEmpty { runsText(runs).split(" • ").first().trim().takeIf { seconds(it) == 0 } ?: "" }
        val album = runs?.let { r -> (0 until r.length()).mapNotNull { r.optJSONObject(it) }.firstOrNull { ALBUM_ID.matches(it.at("navigationEndpoint", "browseEndpoint", "browseId") as? String ?: "") }?.optString("text") }
        val square = thumb != null && Regex("googleusercontent\\.com|ggpht\\.com").containsMatchIn(thumb)
        return Track(id, title.ifEmpty { "Untitled" }, artist, album, duration,
            if (square) sized(thumb, 120) else videoThumb(id), if (square) sized(thumb, 544) else videoArt(id), if (square) kind else "video")
    }

    private fun albumCard(item: JSONObject): AlbumCard? {
        val id = browseIdOf(item).takeIf { ALBUM_ID.matches(it) } ?: return null
        val subtitle = item.optJSONObject("subtitle")
        val (thumb, art) = images(item)
        return AlbumCard(id, text(item.opt("title")), artistsOf(subtitle?.optJSONArray("runs")).joinToString(", ") { it.second }, YEAR.find(text(subtitle))?.value ?: "", thumb, art)
            .takeIf { it.title.isNotEmpty() }
    }

    private fun distinct(tracks: List<Track>) = tracks.distinctBy { it.id }

    /** The same song twice (another upload, a playlist repeat) only shows once. */
    private fun uniqueTracks(tracks: List<Track>): List<Track> {
        val seen = HashSet<String>()
        return tracks.filter { t -> val key = "${t.title}|${t.artist}".lowercase(); (t.id !in seen && key !in seen).also { if (it) { seen += t.id; seen += key } } }
    }

    private fun <T> roundRobin(lists: List<List<T>>): List<T> = buildList { var i = 0; while (lists.any { i < it.size }) { lists.forEach { l -> if (i < l.size) add(l[i]) }; i++ } }

    /** "Céu Azul (Ao Vivo)" → "Céu Azul": enough to recognize the song in a short preview. */
    private fun bareTitle(t: String) = t.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim().ifEmpty { t }

    /** Ranks the artists credited on the items by how often they show up (times the source's weight), skipping known ones. */
    private fun rankArtists(sources: List<Triple<List<JSONObject>, Int, Boolean>>, known: Set<String>): List<GenreArtist> {
        class Entry(val id: String, val name: String, val thumb: String, val art: String) { var score = 0; val hits = mutableListOf<String>() }
        val ranked = LinkedHashMap<String, Entry>()
        for ((items, weight, hits) in sources) for (item in items) {
            val isRow = item.has("flexColumns")
            val runs = if (isRow) JSONArray().also { a -> for (c in 1 until (item.optJSONArray("flexColumns")?.length() ?: 0)) columnRuns(item, c)?.let { for (i in 0 until it.length()) a.put(it.opt(i)) } } else item.optJSONObject("subtitle")?.optJSONArray("runs")
            val hit = if (hits) bareTitle(if (isRow) columnText(item, 0) else text(item.opt("title"))) else ""
            for ((aid, name) in artistsOf(runs, artistPagesOnly = true)) {
                if (aid in known || name.isBlank()) continue
                val e = ranked.getOrPut(aid) { val (t, a) = images(item); Entry(aid, name, t, a) }
                e.score += weight
                if (hit.isNotEmpty() && e.hits.size < 3 && hit !in e.hits) e.hits += hit
            }
        }
        return ranked.values.sortedByDescending { it.score }.map { GenreArtist(it.id, it.name, it.thumb, it.art, it.hits) }
    }
}
