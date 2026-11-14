package app.musike.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Request
import org.json.JSONArray

/** Songs already on the phone (not from Medify) have ids like "local:123", their MediaStore id. */
const val LOCAL = "local:"

/** The phone's music library can be read (and, before Android 10, written) with these. */
val storagePermissions = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

/**
 * Songs saved on the phone, to play with no connection: the audio in the phone's Music/Medify folder (seen by the
 * Files app and other players), the tracks' details in downloads/index.json with each file's URI. Before Android 10
 * without the storage permission they go to the app's own storage. PlaybackService plays the file instead of the
 * stream when there is one. [phone] is the music that was already on the phone.
 */
object Downloads {
    private lateinit var dir: File
    private val index get() = File(dir, "index.json")
    private val _done = MutableStateFlow<List<Track>>(emptyList())
    val done = _done.asStateFlow()
    /** Tracks waiting or downloading, with their progress (0 to 1). */
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active = _active.asStateFlow()
    // ponytail: one at a time, in the app's process (no foreground service): a long batch stops if Android kills the
    // app in the background; what finished stays. a download service if that turns out to matter.
    private val worker = Executors.newSingleThreadExecutor()
    private val uris = ConcurrentHashMap<String, Uri>()
    /** Each download's related songs (YouTube Music's radio for it), saved with it: what the suggestions start from. */
    private val related = ConcurrentHashMap<String, List<Track>>()
    private val _phone = MutableStateFlow<List<Track>>(emptyList())
    val phone = _phone.asStateFlow()
    private val cr get() = Musike.app.contentResolver
    private val publicDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Medify")

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } // left by a download the app was closed during
        _done.value = runCatching {
            JSONArray(index.readText()).let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i)
                    // before Music/Medify, downloads were files named by id in the app's storage
                    Track.from(o)?.also {
                        uris[it.id] = o.optString("uri").ifEmpty { null }?.let(Uri::parse) ?: Uri.fromFile(File(dir, it.id))
                        o.optJSONArray("related")?.let { a -> related[it.id] = (0 until a.length()).mapNotNull { j -> Track.from(a.optJSONObject(j)) } }
                    }
                }
            }
        }.getOrDefault(emptyList())
        refresh()
    }

    /** Forgets downloads deleted outside the app, moves old ones to Music/Medify, and reads the phone's music again. */
    fun refresh() = worker.execute {
        _done.update { l -> l.filter { exists(uris[it.id]) } }
        if (Build.VERSION.SDK_INT >= 29 || writable()) for (t in _done.value) {
            val f = uris[t.id]?.takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.parentFile == dir } ?: continue
            runCatching { uris[t.id] = f.inputStream().use { store(t, sniff(f), it, f.length()) }; f.delete() }
        }
        // downloads from before their details were saved (or saved offline) get them now
        for (t in _done.value) if (!related.containsKey(t.id)) details(t)
        save()
        scan()
    }

    /** The playable file of a download or a song on the phone; null to stream it. */
    fun uri(id: String): Uri? =
        if (id.startsWith(LOCAL)) ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.removePrefix(LOCAL).toLongOrNull() ?: 0)
        else uris[id]?.takeIf { isDone(id) && exists(it) }

    fun isDone(id: String) = _done.value.any { it.id == id }
    fun related(id: String) = related[id]
    private fun details(t: Track) = runCatching { related[t.id] = YouTube.radio(t.id).take(25) }

    fun add(tracks: List<Track>) {
        val todo = tracks.distinctBy { it.id }.filter { !isDone(it.id) && it.id !in _active.value }
        if (todo.isEmpty()) return
        _active.update { it + todo.associate { t -> t.id to 0f } }
        todo.forEach { t -> worker.execute { fetch(t) } }
        Ui.toast(str.downloading(todo.size))
    }

    fun remove(id: String) {
        uris.remove(id)?.let(::erase)
        related.remove(id)
        _done.update { l -> l.filter { it.id != id } }
        save()
    }

    private fun fetch(t: Track) {
        try {
            // twice: a stream URL can be refused (expired, bot check); the second try looks it up again
            var error: Exception? = null
            for (attempt in 1..2) {
                try { uris[t.id] = download(t); error = null; break } catch (e: Exception) { error = e; YouTube.forget(t.id) }
            }
            error?.let { throw it }
            details(t)
            _done.update { l -> listOf(t) + l.filter { it.id != t.id } }
            save()
        } catch (e: Exception) {
            ContextCompat.getMainExecutor(Musike.app).execute { Ui.toast(str.downloadFailed(t.title)) }
        } finally {
            _active.update { it - t.id }
        }
    }

    private fun download(t: Track): Uri {
        val url = YouTube.resolve(t.id).url
        // without a Range header YouTube throttles the download to a crawl
        val req = Request.Builder().url(url).header("User-Agent", YouTube.STREAM_UA).header("Range", "bytes=0-").build()
        YouTube.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body!!
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
            var shown = -1
            return body.byteStream().use { store(t, mime, it, body.contentLength()) { pct -> if (pct != shown) { shown = pct; _active.update { a -> a + (t.id to pct / 100f) } } } }
        }
    }

    /** Writes a song to Music/Medify as "Artist - Title", named for people browsing their files. */
    private fun store(t: Track, mime: String, input: InputStream, total: Long, progress: (Int) -> Unit = {}): Uri {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "m4a"
        val name = listOf(t.artist, t.title).filter { it.isNotBlank() }.joinToString(" - ").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100).trim().ifEmpty { t.id }
        val uri = if (Build.VERSION.SDK_INT >= 29) cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.$ext") // the phone adds " (1)" when the name is taken
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Medify")
            put(MediaStore.Audio.Media.TITLE, t.title)
            put(MediaStore.Audio.Media.ARTIST, t.artist)
            put(MediaStore.Audio.Media.ALBUM, t.album)
            put(MediaStore.Audio.Media.IS_PENDING, 1) // hidden from other apps until it's whole
        }) ?: throw IOException("No room on the phone")
        else {
            val folder = if (writable()) publicDir.apply { mkdirs() } else dir
            Uri.fromFile(File(folder, "$name.$ext").takeIf { !it.exists() } ?: File(folder, "$name ${t.id}.$ext"))
        }
        try {
            var read = 0L
            cr.openOutputStream(uri)!!.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    progress(if (total > 0) (read * 100 / total).toInt() else 0)
                }
            }
            if (total > 0 && read < total) throw IOException("Download cut short")
            if (uri.scheme == "content") cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            else if (!uri.path!!.startsWith(dir.path)) MediaScannerConnection.scanFile(Musike.app, arrayOf(uri.path), arrayOf(mime), null)
            return uri
        } catch (e: Exception) { erase(uri); throw e }
    }

    /** The music already on the phone, minus Medify's own downloads (those show in Downloads). */
    private fun scan() {
        val mine = uris.values.mapNotNull { if (it.scheme == "file") it.path else it.lastPathSegment }.toSet()
        val songs = mutableListOf<Track>()
        // without the permission this sees only Medify's own files (Android 10+) or throws (before)
        runCatching {
            cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id.toString() in mine || c.getString(6) in mine) continue
                    val art = "content://media/external/audio/albumart/${c.getLong(4)}"
                    songs += Track("$LOCAL$id", c.getString(1) ?: "", c.getString(2)?.takeIf { it != MediaStore.UNKNOWN_STRING } ?: "",
                        c.getString(3)?.takeIf { it != MediaStore.UNKNOWN_STRING }, (c.getLong(5) / 1000).toInt(), art, art, "song")
                }
            }
        }
        _phone.value = songs
    }

    private fun writable() = ContextCompat.checkSelfPermission(Musike.app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun exists(uri: Uri?) = uri != null && runCatching { cr.openFileDescriptor(uri, "r")!!.close(); true }.getOrDefault(false)
    private fun erase(uri: Uri) { runCatching { if (uri.scheme == "file") File(uri.path!!).delete() else cr.delete(uri, null, null) } }
    /** An old download's format, from its first bytes: WebM (Opus) or MP4 (AAC). */
    private fun sniff(f: File) = ByteArray(4).also { b -> f.inputStream().use { it.read(b) } }
        .let { if (it.contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) "audio/webm" else "audio/mp4" }

    private fun save() {
        val json = JSONArray().apply { _done.value.forEach { put(it.toJson().put("uri", uris[it.id]?.toString())
            .put("related", related[it.id]?.let { r -> JSONArray().apply { r.forEach { put(it.toJson()) } } })) } }.toString()
        synchronized(this) { File(dir, "index.tmp").apply { writeText(json); renameTo(index) } }
    }
}
