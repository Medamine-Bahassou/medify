package app.musike.android

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// A track travels as a MediaItem whose URI names the video ("musike:///<id>"); the stream URL is only looked up when
// the player loads it, so a long queue never holds URLs that expire before their turn.

/** [from] is where the queue was started (an album, a playlist, a search…), shown over the player. */
fun Track.toItem(from: String = ""): MediaItem = MediaItem.Builder().setMediaId(id).setUri("musike:///$id").setMediaMetadata(
    MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        // a video's hq720 is missing on some uploads; mqdefault always exists
        .setArtworkUri(Uri.parse(if (kind == "video") thumb else art.ifEmpty { thumb }))
        .setExtras(Bundle().apply { putString("thumb", thumb); putString("art", art); putInt("duration", duration); putString("kind", kind); putString("from", from) })
        .build()
).build()

fun MediaItem.toTrack(): Track {
    val m = mediaMetadata
    val e = m.extras
    return Track(mediaId, m.title?.toString() ?: "", m.artist?.toString() ?: "", m.albumTitle?.toString(), e?.getInt("duration") ?: 0,
        e?.getString("thumb") ?: "", e?.getString("art") ?: "", e?.getString("kind") ?: "song")
}

/**
 * Plays the music, also with the screen off or the app closed: Media3 gives it the notification, the lock screen and
 * headphone/Bluetooth buttons, and pauses when headphones are unplugged.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val retried = HashSet<String>()
    private var radioFor: String? = null

    companion object {
        /** The player, for what runs beside it in this process (the sleep timer). */
        var player: ExoPlayer? = null
    }

    override fun onCreate() {
        super.onCreate()
        Musike.init(this)
        val http = DefaultHttpDataSource.Factory().setUserAgent(YouTube.STREAM_UA).setAllowCrossProtocolRedirects(true)
        // DefaultDataSource reads both: YouTube's streams over HTTP and downloaded songs from their files
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme != "musike") return@Factory spec
            val id = spec.uri.lastPathSegment!!
            // a downloaded song (or one that was already on the phone) plays from its file, also offline
            Downloads.uri(id)?.let { return@Factory spec.withUri(it) }
            val url = YouTube.resolve(id).url
            // a request without a Range header is throttled to a crawl; "bytes=0-" downloads at full speed.
            // (the data source replaces this with its own when it seeks.)
            spec.withUri(Uri.parse(url)).withAdditionalHeaders(mapOf("Range" to "bytes=${spec.position}-"))
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // songs already on the phone stay out of the library and radio: their ids mean nothing on YouTube
                if (item == null || item.mediaId.startsWith(LOCAL)) return
                Library.pushHistory(item.toTrack())
                if (Musike.autoRadio && player.repeatMode == Player.REPEAT_MODE_OFF && !player.hasNextMediaItem()) keepGoing(player, item.mediaId)
            }

            override fun onPlayerError(error: PlaybackException) {
                // a stream URL that expired or was refused: look it up again, once, from where it stopped
                val id = player.currentMediaItem?.mediaId ?: return
                if (retried.add(id)) { YouTube.forget(id); player.prepare(); player.play() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) retried.clear() }

            // the sleep timer's "end of this song" has done its job
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.cancel()
            }
        })
        PlaybackService.player = player
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_medify) })
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setCallback(object : MediaSession.Callback {
                // items from a controller lose their URI on the way here: rebuild it from the id
                override fun onAddMediaItems(s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(items.map { it.buildUpon().setUri("musike:///${it.mediaId}").build() }.toMutableList())
            })
            .build()
    }

    /** Autoplay radio: when the queue reaches its last track, similar songs are added after it. */
    private fun keepGoing(player: ExoPlayer, id: String) {
        if (radioFor == id) return
        radioFor = id
        Thread {
            val more = runCatching { YouTube.radio(id) }.getOrDefault(emptyList())
            ContextCompat.getMainExecutor(this).execute {
                val have = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                if (player.currentMediaItem?.mediaId == id) player.addMediaItems(more.filter { it.id !in have }.take(25).map { it.toItem(str.autoplay) })
            }
        }.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiping the app away keeps the music going; with nothing playing the service goes too
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        SleepTimer.cancel()
        PlaybackService.player = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** The screens' side of the player: a controller connected to PlaybackService, mirrored into Compose state. */
object Playback {
    var controller by mutableStateOf<MediaController?>(null)
    var current by mutableStateOf<Track?>(null)
    /** The queue in play order (shuffled when shuffle is on), each with its index in the player. */
    var queue by mutableStateOf<List<Pair<Int, Track>>>(emptyList())
    var playing by mutableStateOf(false)
    /** Where the playing song's queue was started. */
    var from by mutableStateOf("")
    var loading by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    /** Set when a track fails to play; the activity shows it and clears it. */
    var error by mutableStateOf<String?>(null)

    fun connect(ctx: Context) {
        if (controller != null) return
        // bound with the app's context, not the activity's: an activity Android recreates (a wallpaper's new colours)
        // has its bindings torn down, and Media3 then crashes unbinding this one a second time
        val app = ctx.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync(c)
                override fun onPlayerError(e: PlaybackException) { error = e.cause?.message ?: e.message }
            })
            controller = c
            sync(c)
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() { controller?.release(); controller = null }

    private fun sync(c: Player) {
        current = c.currentMediaItem?.toTrack()
        from = c.currentMediaItem?.mediaMetadata?.extras?.getString("from") ?: ""
        playing = c.isPlaying
        loading = c.playbackState == Player.STATE_BUFFERING
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
        val tl = c.currentTimeline
        val order = mutableListOf<Int>()
        var i = if (tl.isEmpty) C.INDEX_UNSET else tl.getFirstWindowIndex(c.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && order.size < tl.windowCount) { order += i; i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled) }
        queue = order.filter { it < c.mediaItemCount }.map { it to c.getMediaItemAt(it).toTrack() }
    }

    fun play(tracks: List<Track>, start: Int = 0, shuffled: Boolean = false, from: String = "") {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        // tapping the song that's already playing (or paused) carries on with it instead of starting it over
        if (!shuffled && tracks.getOrNull(start)?.id == c.currentMediaItem?.mediaId && c.playbackState != Player.STATE_ENDED) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
            return
        }
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(tracks.map { it.toItem(from) }, if (shuffled) tracks.indices.random() else start, 0)
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(tracks)
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { it.toItem(from) })
    }

    fun addToQueue(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(tracks)
        c.addMediaItems(tracks.map { it.toItem(from) })
    }

    /** Stops the music and empties the queue: the mini player and the notification go. */
    fun stop() = controller?.run { stop(); clearMediaItems() }
    fun toggle() = controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } }
    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seek(ms: Long) = controller?.seekTo(ms)
    fun jump(index: Int) = controller?.run { seekTo(index, 0); play() }
    fun remove(index: Int) = controller?.removeMediaItem(index)
    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
}

/**
 * Pauses the music after some minutes, or when the playing song ends. It runs beside the player in the app's process
 * (the screens can be gone; the playback service keeps the process alive while music plays).
 */
@OptIn(UnstableApi::class)
object SleepTimer {
    /** When the music stops (ms), for a timer in minutes. */
    var endsAt by mutableStateOf<Long?>(null)
        private set
    var endOfSong by mutableStateOf(false)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val stop = Runnable { PlaybackService.player?.pause(); endsAt = null }

    fun after(minutes: Int) {
        cancel()
        endsAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(stop, minutes * 60_000L)
    }

    fun atEndOfSong() {
        cancel()
        endOfSong = true
        // ExoPlayer pauses itself once the playing song ends, before the next one starts
        PlaybackService.player?.pauseAtEndOfMediaItems = true
    }

    fun cancel() {
        handler.removeCallbacks(stop)
        endsAt = null
        if (endOfSong) { endOfSong = false; PlaybackService.player?.pauseAtEndOfMediaItems = false }
    }
}
