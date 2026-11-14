@file:OptIn(ExperimentalMaterial3Api::class)

package app.musike.android

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Musike.init(this)
        Playback.connect(this)
        setContent {
            MedifyTheme {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                LaunchedEffect(dark) {
                    val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    enableEdgeToEdge(bars, bars)
                }
                // back steps out of a page, then to Home, then sends the app to the background (the music keeps playing)
                BackHandler { if (!Nav.back()) moveTaskToBack(true) }
                // Arabic reads right to left, whatever the phone's own language
                CompositionLocalProvider(LocalLayoutDirection provides if (Musike.lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) { Shell() }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) Playback.release()
        super.onDestroy()
    }
}

// ---- navigation: one back stack per tab

sealed interface Route
data object HomeR : Route
data object SearchR : Route
data object LibraryR : Route
data object SettingsR : Route
data object GenresR : Route
data object FavoritesR : Route
data object HistoryR : Route
data object DownloadsR : Route
data object PhoneR : Route
data object SmartR : Route
data class GenreR(val id: String, val name: String) : Route
data class ArtistR(val id: String) : Route
data class AlbumR(val id: String) : Route
data class PlaylistR(val id: String) : Route

enum class Tab(val root: Route) { Home(HomeR), Search(SearchR), Library(LibraryR) }

object Nav {
    var tab by mutableStateOf(Tab.Home)
    val stacks = Tab.entries.associateWith { mutableStateListOf(it.root) }
    val route get() = stacks[tab]!!.last()
    /** Counts the times Search was closed: a new count starts it over, empty. */
    var searches by mutableIntStateOf(0)
    fun go(r: Route) { stacks[tab]!!.add(r) }
    fun back(): Boolean {
        val s = stacks[tab]!!
        if (s.size > 1) { s.removeAt(s.lastIndex); return true }
        if (tab != Tab.Home) { if (tab == Tab.Search) searches++; tab = Tab.Home; return true }
        return false
    }
}

/** The list a track menu was opened from, when it's History (otherwise it's a playlist's id, or nothing). */
const val HISTORY = "history"

/** Sheets and dialogs any screen can open. */
object Ui {
    var menu by mutableStateOf<Pair<Track, String?>?>(null)
    var addTo by mutableStateOf<List<Track>?>(null)
    var prompt by mutableStateOf<Triple<String, String, (String) -> Unit>?>(null)
    var confirm by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var nowPlaying by mutableStateOf(false)
    /** The song menu was opened from the player: its own options instead of the list's. */
    var playerMenu by mutableStateOf(false)
    var sleep by mutableStateOf(false)
    /** The playlist the "add songs" sheet is filling. */
    var addSongsTo by mutableStateOf<String?>(null)
    fun toast(msg: String) = Toast.makeText(Musike.app, msg, Toast.LENGTH_SHORT).show()

    // ---- picking songs in a list (long-press one, then tap others) to act on them all at once
    var picked by mutableStateOf<Set<String>>(emptySet())
    /** The list the songs are picked in, and where it is (a playlist's id, History, or nothing). */
    var pickList by mutableStateOf<List<Track>>(emptyList())
    var pickIn by mutableStateOf<String?>(null)
    fun pick(t: Track, list: List<Track>, where: String?) {
        if (picked.isEmpty()) { pickList = list; pickIn = where }
        picked = if (t.id in picked) picked - t.id else picked + t.id
    }
    fun unpick() { picked = emptySet() }
}

private data class Place(val tab: Tab, val depth: Int, val route: Route)

@Composable
fun Shell() {
    val holder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    var bars by remember { mutableStateOf(0.dp) }
    val stack = Nav.stacks[Nav.tab]!!
    // Settings has no dock; the mini player stays on every page while something plays
    val settings = stack.last() == SettingsR
    // the pages draw into this layer; the dock and mini player, drawn beside it, are glass over it
    val pages = rememberLayerBackdrop()
    // while something plays, scrolling down a page tucks the dock into one round button beside the mini player;
    // scrolling up brings it back. with nothing playing the dock stays as it is.
    var small by remember { mutableStateOf(false) }
    val scrolling = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) small = true else if (available.y > 6f) small = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(Nav.tab, stack.size) { small = false }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().nestedScroll(scrolling).glassSource(pages).background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalBottomSpace provides bars) {
            AnimatedContent(
                Place(Nav.tab, stack.size, stack.last()),
                transitionSpec = {
                    when {
                        initialState.tab != targetState.tab -> fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                        targetState.depth < initialState.depth -> Motion.backIn togetherWith Motion.backOut
                        else -> Motion.forwardIn togetherWith Motion.forwardOut
                    }
                },
                label = "pages",
            ) { place -> holder.SaveableStateProvider("${place.tab}/${place.depth}/${place.route}") { Screen(place.route) } }
        }
        }
        CompositionLocalProvider(LocalGlass provides pages) {
            Column(
                // measured before its padding and the system bar's inset, so lists keep clear of all of it
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bars = with(density) { it.height.toDp() } }
                    .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // picking songs: their actions take the dock's place
                if (Ui.picked.isNotEmpty()) PickBar()
                else AnimatedContent(small && !settings && Playback.current != null, transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) using SizeTransform(clip = false) },
                    label = "dock") { tucked ->
                    if (tucked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // the open tab, round; a tap brings the whole dock back
                        Box(Modifier.size(60.dp).tap(CircleShape, 0.92f) { small = false }.glass(CircleShape), Alignment.Center) {
                            Icon(tabIcon(Nav.tab), null, tint = MaterialTheme.colorScheme.onSurface)
                        }
                        MiniPlayer(Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniPlayer()
                        if (!settings) Dock()
                    }
                }
            }
        }
    }
    // leaving the page, or back, ends the picking
    LaunchedEffect(Nav.tab, stack.size, stack.last()) { Ui.unpick() }
    BackHandler(Ui.picked.isNotEmpty()) { Ui.unpick() }
    if (Ui.nowPlaying && Playback.current != null) NowPlaying()
    Ui.menu?.let { (t, playlist) -> TrackMenu(t, playlist) }
    if (Ui.sleep) SleepSheet()
    Ui.addSongsTo?.let { AddSongs(it) }
    Ui.addTo?.let { AddToPlaylist(it) }
    Ui.prompt?.let { (title, initial, done) -> Prompt(title, initial, done) }
    Ui.confirm?.let { (text, yes) ->
        AlertDialog(onDismissRequest = { Ui.confirm = null }, text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton({ Ui.confirm = null; yes() }) { Text(str.delete) } },
            dismissButton = { TextButton({ Ui.confirm = null }) { Text(str.cancel) } })
    }
    val err = Playback.error
    LaunchedEffect(err) {
        if (err == null) return@LaunchedEffect
        // the service looks the stream up again once by itself: only speak up if that didn't fix it
        delay(4000)
        if (Playback.controller?.playerError != null) Ui.toast(str.playbackFailed)
        Playback.error = null
    }
}

@Composable
fun Screen(r: Route) = CompositionLocalProvider(LocalPlayingFrom provides when (r) {
    HomeR -> str.home
    LibraryR -> str.library
    FavoritesR -> str.favorites
    HistoryR -> str.history
    DownloadsR -> str.downloads
    PhoneR -> str.onPhone
    is GenreR -> r.name
    is PlaylistR -> Library.state.value.playlists.firstOrNull { it.id == r.id }?.name ?: str.playlists
    else -> ""
}) { Page(r) }

@Composable
private fun Page(r: Route) = when (r) {
    HomeR -> HomeScreen()
    SearchR -> SearchScreen()
    LibraryR -> LibraryScreen()
    SettingsR -> SettingsScreen()
    GenresR -> GenresScreen()
    FavoritesR, HistoryR, DownloadsR, PhoneR, is PlaylistR -> CollectionScreen(r)
    SmartR -> SmartScreen()
    is GenreR -> GenreScreen(r)
    is ArtistR -> ArtistScreen(r.id)
    is AlbumR -> AlbumScreen(r.id)
}

fun clock(seconds: Int) = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)

/** The player's position and length in ms, refreshed twice a second while shown. */
@Composable
fun rememberProgress(): Pair<Long, Long> {
    var p by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(Unit) {
        while (true) {
            Playback.controller?.let { c -> p = c.currentPosition to (c.duration.takeIf { it > 0 } ?: ((Playback.current?.duration ?: 0) * 1000L)) }
            delay(500)
        }
    }
    return p
}

// ---- the floating bars: the mini player and the dock, both pills

/** The tabs. The open one grows to show its name inside a liquid-glass bubble, a lens over the page like the dock
 *  itself, that slides from tab to tab. */
private fun tabIcon(tab: Tab) = when (tab) { Tab.Home -> Icons.Default.Home; Tab.Search -> Icons.Default.Search; Tab.Library -> Ic.library }

@Composable
private fun Dock() {
    val tabs = Tab.entries
    val weights = tabs.map { t -> animateFloatAsState(if (Nav.tab == t) 1.6f else 1f, Motion.move(), label = "tabWeight").value }
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    val gap = 4.dp
    // where the open tab sits: its share of the row, from the same animated weights the row lays out with
    val gapPx = with(density) { gap.toPx() }
    val unit = (width - gapPx * (tabs.size - 1)) / weights.sum()
    val i = tabs.indexOf(Nav.tab)
    val bubbleStart = weights.take(i).sum() * unit + i * gapPx
    val bubbleWidth = with(density) { (weights[i] * unit).coerceAtLeast(0f).toDp() }
    Box(Modifier.glass(PillShape)) {
        Box(Modifier.padding(6.dp).height(52.dp).fillMaxWidth().onSizeChanged { width = it.width }) {
            if (width > 0) Box(
                Modifier.offset { IntOffset(bubbleStart.roundToInt(), 0) }.width(bubbleWidth).fillMaxHeight()
                    .glass(PillShape, lerp(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, 0.12f)),
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                tabs.forEachIndexed { n, tab ->
                    val on = Nav.tab == tab
                    val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, Motion.fade(), label = "tabInk")
                    val label = when (tab) { Tab.Home -> str.home; Tab.Search -> str.search; Tab.Library -> str.library }
                    Row(
                        Modifier.weight(weights[n]).fillMaxHeight()
                            // tapping the open tab goes back to its first page
                            .tap(PillShape) { if (on) Nav.stacks[tab]!!.let { s -> while (s.size > 1) s.removeAt(s.lastIndex) } else Nav.tab = tab },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tabIcon(tab), label, tint = fg)
                        AnimatedVisibility(on, enter = expandHorizontally(Motion.move()) + fadeIn(Motion.fade()), exit = shrinkHorizontally(Motion.move()) + fadeOut(Motion.fade())) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** What to do with the picked songs, in one go. Songs already on the phone only play: the rest skips them. */
@Composable
private fun PickBar() {
    val songs = Ui.pickList.filter { it.id in Ui.picked }
    val online = songs.filter { !it.id.startsWith(LOCAL) }
    val lib by Library.state.collectAsState()
    val allFav = online.isNotEmpty() && online.all { t -> lib.favorites.any { it.id == t.id } }
    val where = Ui.pickIn
    fun done(act: () -> Unit) { act(); Ui.unpick() }
    Column(Modifier.glass(RoundedCornerShape(28.dp)).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ Ui.unpick() }) { Icon(Icons.Default.Close, str.cancel) }
            Text(str.picked(songs.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val all = Ui.picked.size == Ui.pickList.size
            IconButton({ Ui.picked = if (all) emptySet() else Ui.pickList.map { it.id }.toSet() }) {
                Icon(Ic.selectAll, str.selectAll, tint = if (all) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton({ done { Playback.play(songs, from = str.picked(songs.size)) } }) { Icon(Icons.Default.PlayArrow, str.play) }
            IconButton({ done { Playback.addToQueue(songs) } }) { Icon(Ic.queue, str.addToQueue) }
            if (online.isNotEmpty()) {
                IconButton({ done { Ui.addTo = online } }) { Icon(Ic.playlistAdd, str.addToPlaylist) }
                IconButton({ done { Library.setFavorites(online, !allFav) } }) {
                    Icon(if (allFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (allFav) str.removeFromFavorites else str.addToFavorites,
                        tint = if (allFav) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                // all downloaded already (the Downloads list): the same button takes them off the phone
                val allDown = online.all { Downloads.isDone(it.id) }
                IconButton({ done { if (allDown) online.forEach { Downloads.remove(it.id) } else Downloads.add(online) } }) {
                    Icon(if (allDown) Ic.downloaded else Ic.download, if (allDown) str.removeDownload else str.download,
                        tint = if (allDown) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
            if (where != null) IconButton({
                val ids = songs.map { it.id }.toSet()
                done { if (where == HISTORY) Library.removeFromHistory(ids) else Library.removeFromPlaylist(where, ids) }
            }) { Icon(Icons.Default.Delete, if (where == HISTORY) str.removeFromHistory else str.removeFromPlaylist) }
        }
    }
}

@Composable
private fun MiniPlayer(modifier: Modifier = Modifier) {
    val t = Playback.current ?: return
    val (pos, dur) = rememberProgress()
    // swiped down it stops the music and goes; swiped up it opens the player; let go short of either, it springs back
    // the finger moves it directly (no coroutine per move: one landing late would cancel the settle and leave it
    // parked behind the dock); letting go animates it home, or away
    var drag by remember { mutableFloatStateOf(0f) }
    val far = with(LocalDensity.current) { 56.dp.toPx() }
    // a new song brings it back, even right after one was swiped away
    LaunchedEffect(t.id) { drag = 0f }
    suspend fun settle(to: Float) = animate(drag, to, animationSpec = tween(220)) { v, _ -> drag = v }
    Box(modifier
        .offset { IntOffset(0, drag.roundToInt()) }
        // gone by the time it reaches the dock, so it never shows tucked behind it
        .graphicsLayer { alpha = 1f - (drag / far).coerceIn(0f, 1f) }
        .draggable(rememberDraggableState { d -> drag = (drag + d).coerceAtLeast(-far / 2) }, Orientation.Vertical,
            onDragStopped = { v ->
                when {
                    drag > far / 2 || v > 1500f -> { settle(far); Playback.stop() }
                    drag < -far / 3 || v < -1500f -> { Ui.nowPlaying = true; settle(0f) }
                    else -> settle(0f)
                }
            })
        .tap(PillShape, 0.98f) { Ui.nowPlaying = true }.glass(PillShape)) {
        Box {
            Row(Modifier.padding(6.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(t.thumb, 48.dp, CircleShape)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlayPause(48.dp)
                IconButton({ Playback.next() }) { Icon(Ic.next, null) }
            }
            // progress: a hairline along the pill's bottom, inside its curve
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 28.dp).fillMaxWidth().height(2.dp).clip(PillShape)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** Play/pause as a filled circle; while it's playing the circle squares off a little (Material 3 Expressive). */
@Composable
private fun PlayPause(size: androidx.compose.ui.unit.Dp) {
    val corner by animateDpAsState(if (Playback.playing) size * 0.3f else size / 2, Motion.move(), label = "playShape")
    Box(Modifier.size(size).tap(RoundedCornerShape(corner), 0.9f) { Playback.toggle() }.background(MaterialTheme.colorScheme.primary), Alignment.Center) {
        if (Playback.loading) CircularProgressIndicator(Modifier.size(size * 0.45f), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.5.dp)
        else Icon(if (Playback.playing) Ic.pause else Icons.Default.PlayArrow, str.play, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.5f))
    }
}

// ---- now playing: the artwork leads, its own colours wash the page, controls below

@Composable
private fun NowPlaying() {
    val t = Playback.current ?: return
    var showQueue by remember { mutableStateOf(false) }
    val lib by Library.state.collectAsState()
    val bg = MaterialTheme.colorScheme.background
    // the whole screen, the artwork's colours under the status and navigation bars too
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // slides down like a swipe does, then goes
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { Ui.nowPlaying = false } }
    ModalBottomSheet({ Ui.nowPlaying = false }, sheetState = sheet, containerColor = bg, dragHandle = null,
        shape = RectangleShape, contentWindowInsets = { WindowInsets(0) }) {
        Box(Modifier.fillMaxSize()) {
            val art = if (t.kind == "video") t.thumb else t.art
            // a blurred copy of the artwork tints the page (Android 12+ blurs it; older phones show it faded). the
            // controls are glass over it.
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.matchParentSize().glassSource(backdrop).background(bg)) {
                AsyncImage(art.ifEmpty { null }, null, Modifier.matchParentSize().blur(90.dp).alpha(0.6f), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.1f), bg.copy(alpha = 0.6f), bg))))
            }
            CompositionLocalProvider(LocalGlass provides backdrop) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // collapse (or leave the queue), what's playing and where from, and the song's options
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showQueue) RoundButton(Icons.AutoMirrored.Filled.ArrowBack, str.back, 44.dp, glass = true) { showQueue = false }
                else RoundButton(Icons.Default.KeyboardArrowDown, str.back, 44.dp, glass = true, onClick = close)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((if (showQueue) str.queue else str.nowPlaying).uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, maxLines = 1)
                    Text(Playback.from.ifEmpty { "Medify" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundButton(Icons.Default.MoreVert, str.more, 44.dp, glass = true) { Ui.playerMenu = true; Ui.menu = t to null }
            }
            if (showQueue) Queue() else Column(Modifier.weight(1f).padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp)) {
                // the artwork takes the height the controls leave: the biggest square that fits
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Cover(art, 600.dp, RoundedCornerShape(28.dp), Modifier.aspectRatio(1f)) }
                Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val fav = lib.favorites.any { it.id == t.id }
                    RoundButton(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) str.removeFromFavorites else str.addToFavorites, 48.dp,
                        tint = if (fav) MaterialTheme.colorScheme.primary else Color.Unspecified, glass = true) { Library.toggleFavorite(t) }
                }
                // the seek bar and transport buttons keep their left-to-right order in Arabic too, like every music app
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column {
                        val (pos, dur) = rememberProgress()
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = dragging ?: if (dur > 0) pos.toFloat() / dur else 0f,
                            onValueChange = { dragging = it },
                            onValueChangeFinished = { dragging?.let { Playback.seek((it * dur).toLong()) }; dragging = null },
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            val shown = dragging?.let { (it * dur).toLong() } ?: pos
                            Text(clock((shown / 1000).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(clock((dur / 1000).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(Ic.previous, null, 64.dp, glass = true) { Playback.previous() }
                            BigPlay(Modifier.weight(1f))
                            RoundButton(Ic.next, null, 64.dp, glass = true) { Playback.next() }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallPill(Ic.shuffle, str.shuffle, Playback.shuffle) { Playback.toggleShuffle() }
                    SmallPill(Ic.playlistAdd, str.addToPlaylist) { Ui.addTo = listOf(t) }
                    SmallPill(Ic.queue, str.queue) { showQueue = true }
                    SmallPill(if (Playback.repeat == Player.REPEAT_MODE_ONE) Ic.repeatOne else Ic.repeat, str.repeat, Playback.repeat != Player.REPEAT_MODE_OFF) { Playback.cycleRepeat() }
                }
            }
            }
            }
        }
    }
}

/** The wide play/pause pill of the player, with its word. */
@Composable
private fun BigPlay(modifier: Modifier) {
    val corner by animateDpAsState(if (Playback.playing) 20.dp else 32.dp, Motion.move(), label = "bigPlay")
    Row(modifier.height(64.dp).tap(RoundedCornerShape(corner), 0.95f) { Playback.toggle() }.background(MaterialTheme.colorScheme.primary),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val on = MaterialTheme.colorScheme.onPrimary
        if (Playback.loading) CircularProgressIndicator(Modifier.size(26.dp), color = on, strokeWidth = 3.dp)
        else {
            Icon(if (Playback.playing) Ic.pause else Icons.Default.PlayArrow, null, tint = on, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (Playback.playing) str.pause else str.play, style = MaterialTheme.typography.titleMedium, color = on)
        }
    }
}

/** A secondary player control: a tonal pill that turns primary while its mode is on. */
@Composable
private fun RowScope.SmallPill(icon: ImageVector, description: String, on: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, Motion.fade(), label = "pill")
    Box(Modifier.weight(1f).height(48.dp).tap(PillShape, 0.92f, onClick = onClick).then(if (on) Modifier.background(bg) else Modifier.glass(PillShape)), Alignment.Center) {
        Icon(icon, description, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Queue() = Column(Modifier.fillMaxHeight()) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        val q = Playback.queue
        itemsIndexed(q, key = { _, (i, t) -> "$i/${t.id}" }) { n, (i, t) ->
            val p = posOf(n, q.size)
            val now = Playback.controller?.currentMediaItemIndex == i
            GroupCard(p, Modifier.inGroup(p), highlight = now, onClick = { Playback.jump(i) }) {
                Cover(t.thumb, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (!now) IconButton({ Playback.remove(i) }) { Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---- sheets and dialogs: grouped rows, like everything else

@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(bottom = 16.dp), content = content)
    }

private class SheetRow(val icon: ImageVector, val text: String, val sub: String? = null, val act: () -> Unit)

/** The rows of a sheet as one group; each closes the sheet, then acts. */
@Composable
private fun SheetRows(rows: List<SheetRow>, close: () -> Unit) = rows.forEachIndexed { i, r ->
    GroupRow(r.icon, r.text, posOf(i, rows.size), r.sub, trailing = null) { close(); r.act() }
}

@Composable
private fun TrackMenu(t: Track, playlistId: String?) {
    val ctx = LocalContext.current
    val fav = Library.state.collectAsState().value.favorites.any { it.id == t.id }
    val downloaded = Downloads.done.collectAsState().value.any { it.id == t.id }
    val downloading = t.id in Downloads.active.collectAsState().value
    val inPlayer = Ui.playerMenu
    val close = { Ui.menu = null; Ui.playerMenu = false }
    Sheet(close) {
        Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.thumb, 64.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        SheetRows(buildList {
            // from the player the song is already playing: no "play next", and the sleep timer instead
            if (!inPlayer) add(SheetRow(Ic.playNext, str.playNext) { Playback.playNext(listOf(t)) })
            if (!inPlayer) add(SheetRow(Ic.queue, str.addToQueue) { Playback.addToQueue(listOf(t)) })
            // a song already on the phone only plays: it isn't on YouTube to save, download or share
            if (t.id.startsWith(LOCAL)) { if (inPlayer) add(SheetRow(Ic.timer, str.sleepTimer, sleepState()) { Ui.sleep = true }); return@buildList }
            add(SheetRow(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) str.removeFromFavorites else str.addToFavorites) { Library.toggleFavorite(t) })
            add(SheetRow(Ic.playlistAdd, str.addToPlaylist) { Ui.addTo = listOf(t) })
            if (downloaded) add(SheetRow(Icons.Default.Delete, str.removeDownload) { Downloads.remove(t.id) })
            else if (!downloading) add(SheetRow(Ic.download, str.download) { Downloads.add(listOf(t)) })
            if (playlistId == HISTORY) add(SheetRow(Icons.Default.Delete, str.removeFromHistory) { Library.removeFromHistory(t.id) })
            else if (playlistId != null) add(SheetRow(Icons.Default.Delete, str.removeFromPlaylist) { Library.removeFromPlaylist(playlistId, t.id) })
            if (inPlayer) add(SheetRow(Ic.timer, str.sleepTimer, sleepState()) { Ui.sleep = true })
            add(SheetRow(Icons.Default.Share, str.share) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${t.id}"), null))
            })
        }, close)
    }
}

/** What the sleep timer is set to, in words. */
private fun sleepState(): String = SleepTimer.endsAt?.let { str.stopsAt(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))) }
    ?: if (SleepTimer.endOfSong) str.endOfSong else str.off

@Composable
private fun SleepSheet() {
    val close = { Ui.sleep = false }
    val timed = SleepTimer.endsAt != null
    Sheet(close) {
        Text(str.sleepTimer, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp))
        Text(sleepState(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, bottom = 16.dp))
        val options = listOf(15, 30, 45, 60)
        val count = options.size + 2
        GroupSelect(Ic.timer, str.off, !timed && !SleepTimer.endOfSong, posOf(0, count)) { SleepTimer.cancel(); close() }
        options.forEachIndexed { i, m -> GroupSelect(Ic.timer, str.minutes(m), false, posOf(i + 1, count)) { SleepTimer.after(m); close() } }
        GroupSelect(Ic.timer, str.endOfSong, SleepTimer.endOfSong, posOf(count - 1, count)) { SleepTimer.atEndOfSong(); close() }
    }
}

/** Fills a playlist: search YouTube Music (or, before typing, pick from what you've played and liked); a tap adds a
 *  song, another removes it. */
@Composable
private fun AddSongs(playlistId: String) {
    val lib by Library.state.collectAsState()
    val playlist = lib.playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) { Ui.addSongsTo = null; return }
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    // null while a search runs
    val results by produceState<List<Track>?>(emptyList(), query) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        delay(400)
        value = null
        value = runCatching { withContext(Dispatchers.IO) { YouTube.search(query.trim(), "song") } }.getOrDefault(emptyList())
    }
    val shown = if (query.isBlank()) (lib.history + lib.favorites).distinctBy { it.id } else results ?: emptyList()
    ModalBottomSheet({ Ui.addSongsTo = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text(str.addSongs, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp))
        Text(playlist.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
        TextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text(str.searchHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true, shape = PillShape, textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        if (results == null) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
        LazyColumn(Modifier.fillMaxHeight(0.85f), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            if (results != null && query.isNotBlank() && shown.isEmpty()) item { Nothing(str.noResults) }
            itemsIndexed(shown, key = { _, t -> t.id }) { i, t ->
                val p = posOf(i, shown.size)
                val added = playlist.tracks.any { it.id == t.id }
                GroupCard(p, Modifier.inGroup(p), highlight = added, onClick = {
                    if (added) Library.removeFromPlaylist(playlistId, t.id) else Library.addToPlaylist(playlistId, listOf(t))
                }) {
                    Cover(t.thumb, 48.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBadge(if (added) Icons.Default.Check else Icons.Default.Add, on = added, size = 36.dp)
                }
            }
        }
    }
}

@Composable
private fun AddToPlaylist(tracks: List<Track>) {
    val lib by Library.state.collectAsState()
    Sheet({ Ui.addTo = null }) {
        Text(str.addToPlaylist, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, bottom = 16.dp))
        LazyColumn {
            val n = lib.playlists.size + 1
            item {
                val p = posOf(0, n)
                GroupRow(Icons.Default.Add, str.newPlaylist, p, trailing = null) {
                    Ui.addTo = null
                    Ui.prompt = Triple(str.newPlaylist, "") { name -> Library.createPlaylist(name, tracks); Ui.toast(str.addedTo(name)) }
                }
            }
            itemsIndexed(lib.playlists, key = { _, pl -> pl.id }) { i, pl ->
                val p = posOf(i + 1, n)
                GroupCard(p, Modifier.inGroup(p), onClick = { Ui.addTo = null; Library.addToPlaylist(pl.id, tracks); Ui.toast(str.addedTo(pl.name)) }) {
                    Cover(pl.tracks.firstOrNull()?.thumb ?: "", 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(pl.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(str.songs(pl.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Prompt(title: String, initial: String, done: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { Ui.prompt = null },
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true, label = { Text(str.playlistName) }, shape = MaterialTheme.shapes.medium) },
        confirmButton = { TextButton({ Ui.prompt = null; done(text.trim()) }, enabled = text.isNotBlank()) { Text(if (initial.isEmpty()) str.create else str.rename) } },
        dismissButton = { TextButton({ Ui.prompt = null }) { Text(str.cancel) } },
    )
}
