@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.musike.android

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- shared pieces of the pages

/** Albums or singles as a sideways shelf of covers. */
@Composable
private fun AlbumShelf(albums: List<AlbumCard>) = LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    items(albums, key = { it.id }) { a -> Card(a.art, a.title, listOf(a.artist, a.year).filter { it.isNotEmpty() }.joinToString(" • ")) { Nav.go(AlbumR(a.id)) } }
}

/** Artwork with a title and a line under it: shelves of albums and recently played songs. */
@Composable
private fun Card(art: String, title: String, sub: String, size: Dp = 156.dp, onClick: () -> Unit) =
    Column(Modifier.width(size).tap(TileShape, 0.97f, onClick = onClick)) {
        Cover(art, size, TileShape)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        // room under the text: the card's rounded tap area clips, and its bottom corners would cut into the last line
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))
    }

private fun LazyListScope.playlistRows(lists: List<Playlist>) = itemsIndexed(lists, key = { _, p -> p.id }) { i, pl ->
    val p = posOf(i, lists.size)
    GroupCard(p, Modifier.inGroup(p), onClick = { Nav.go(PlaylistR(pl.id)) }) {
        Cover(pl.tracks.firstOrNull()?.thumb ?: "", 52.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(pl.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(str.songs(pl.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small coloured label over a group, the way Settings names its sections. */
@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 8.dp))

// ---- Home

@Composable
fun HomeScreen() {
    val lib by Library.state.collectAsState()
    val downloads by Downloads.done.collectAsState()
    val phone by Downloads.phone.collectAsState()
    // the last ones show at once; they're thought over again in the background every few hours
    val because by Suggest.home.collectAsState()
    LaunchedEffect(lib.history.size) { Suggest.refreshHome(lib, downloads, phone) }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    Page({
        Header(str.home, back = false) {
            RoundButton(Icons.Default.Search, str.search, 44.dp) { Nav.tab = Tab.Search }
            RoundButton(Icons.Default.Settings, str.settings, 44.dp) { Nav.go(SettingsR) }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(str.greeting(hour), style = MaterialTheme.typography.headlineSmall)
                    Text(DateFormat.getDateInstance(DateFormat.FULL, Locale.forLanguageTag(Musike.lang)).format(Date()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (lib.history.isEmpty() && lib.favorites.isEmpty() && lib.playlists.isEmpty()) item {
                // nothing played yet: one clear thing to do
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(24.dp)) {
                    Text(str.startTitle, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(str.startBody, Modifier.padding(top = 8.dp, bottom = 20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    PillButton(str.search, Icons.Default.Search) { Nav.tab = Tab.Search }
                }
            }
            item {
                LazyRow(Modifier.padding(top = 16.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Tile(Icons.Default.Favorite, str.favorites, str.songs(lib.favorites.size)) { Nav.go(FavoritesR) } }
                    item { Tile(Ic.download, str.downloads, str.songs(downloads.size)) { Nav.go(DownloadsR) } }
                    item { Tile(Ic.phone, str.onPhone, str.songs(phone.size)) { Nav.go(PhoneR) } }
                    item { Tile(Ic.explore, str.genres, "YouTube Music") { Nav.go(GenresR) } }
                    item { Tile(Ic.history, str.history, str.songs(lib.history.size)) { Nav.go(HistoryR) } }
                }
            }
            if (lib.history.isNotEmpty()) {
                item { SectionTitle(str.recentlyPlayed, str.seeAll) { Nav.go(HistoryR) } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(lib.history.take(20), key = { _, t -> t.id }) { i, t ->
                            Card(if (t.kind == "video") t.thumb else t.art, t.title, t.artist, 148.dp) { Playback.play(lib.history, i, from = str.recentlyPlayed); Ui.nowPlaying = true }
                        }
                    }
                }
            }
            // "because you listened to…": songs like ones you love, not played yet
            for ((seed, tracks) in because) {
                val title = str.becauseYou(seed.title)
                item { SectionTitle(title) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                            Card(if (t.kind == "video") t.thumb else t.art, t.title, t.artist, 148.dp) { Playback.play(tracks, i, from = title); Ui.nowPlaying = true }
                        }
                    }
                }
            }
            if (lib.favorites.isNotEmpty()) {
                item { SectionTitle(str.favorites, str.seeAll) { Nav.go(FavoritesR) } }
                trackRows(lib.favorites, limit = 5)
            }
            if (lib.playlists.isNotEmpty()) {
                item { SectionTitle(str.playlists) }
                playlistRows(lib.playlists)
            }
        }
    }
}

/** A quick-access square: badge on top, name and count under it. */
@Composable
private fun Tile(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) =
    Column(Modifier.width(132.dp).tap(TileShape, onClick = onClick).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
        IconBadge(icon)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }

// ---- Search

private val LINK = Regex("(?:youtu\\.be/|[?&]v=|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})")

/** Lowercase, without accents: "Beyoncé" is found by "beyonce". */
private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

@Composable
fun SearchScreen() {
    // closing Search (back to Home) forgets the last search
    var query by rememberSaveable(Nav.searches) { mutableStateOf("") }
    var submitted by rememberSaveable(Nav.searches) { mutableStateOf("") }
    var type by rememberSaveable(Nav.searches) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun submit(q: String) {
        query = q
        // force: a field that still holds focus brings the keyboard straight back
        focus.clearFocus(force = true)
        keyboard?.hide()
        val link = if ("youtu" in q) LINK.find(q)?.groupValues?.get(1) else null
        // a pasted YouTube link plays right away
        if (link != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { YouTube.linked(link) } }
                .onSuccess { Playback.play(listOf(it), from = "YouTube"); Ui.nowPlaying = true }
                .onFailure { Ui.toast(str.playbackFailed) }
        } else if (q.isNotBlank()) submitted = q.trim().take(200)
    }
    // voice search through the phone's own speech recognizer; the mic only shows when there is one
    val voiceIntent = remember(Musike.lang) {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Musike.lang)
    }
    val canVoice = remember { voiceIntent.resolveActivity(ctx.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::submit)
    }
    val types = listOf(str.tabSongs, str.tabVideos, str.tabAlbums, str.artists, str.library)
    val suggesting = focused && query.isNotBlank() && query.trim() != submitted
    Page({
        HeaderPane {
            Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Filled.ArrowBack, str.back, 52.dp) { Nav.back() }
                TextField(
                    query, { query = it },
                    // a glass pill, like the header's round buttons
                    Modifier.weight(1f).padding(start = 10.dp).glass(PillShape).onFocusChanged { focused = it.isFocused },
                    placeholder = { Text(str.searchHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Clear, null) } },
                    singleLine = true,
                    shape = PillShape,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                )
                if (canVoice) IconButton({ voice.launch(voiceIntent) }, Modifier.size(52.dp)) { Icon(Ic.mic, str.voice) }
                else Spacer(Modifier.width(4.dp))
            }
            LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(types) { i, label -> Chip(label, type == i) { type = i } }
            }
        }
    }) {
        when {
            suggesting -> {
                val suggestions by produceState(emptyList<String>(), query) {
                    delay(250)
                    value = runCatching { withContext(Dispatchers.IO) { YouTube.suggestions(query.trim().take(100)) } }.getOrDefault(emptyList())
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                    itemsIndexed(suggestions) { i, s ->
                        val p = posOf(i, suggestions.size)
                        GroupCard(p, Modifier.inGroup(p), onClick = { submit(s) }) {
                            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(s, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }
            // nothing searched yet: genres and moods to start from
            submitted.isEmpty() -> Loader("genres|${Musike.lang}", { YouTube.genres() }) { genres ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                    item {
                        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ic.explore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Text(str.exploreGenres, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    item {
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (g in genres) Chip(g.name) { Nav.go(GenreR(g.id, g.name)) }
                        }
                    }
                }
            }
            type == 2 -> Loader("$submitted|albums|${Musike.lang}", { YouTube.searchAlbums(submitted) }) { albums ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                    if (albums.isEmpty()) item { Nothing(str.noResults) }
                    itemsIndexed(albums, key = { _, a -> a.id }) { i, a ->
                        val p = posOf(i, albums.size)
                        GroupCard(p, Modifier.inGroup(p), onClick = { Nav.go(AlbumR(a.id)) }) {
                            Cover(a.thumb, 52.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(a.artist, a.year).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            type == 3 -> Loader("$submitted|artists|${Musike.lang}", { YouTube.searchArtists(submitted) }) { artists ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                    if (artists.isEmpty()) item { Nothing(str.noResults) }
                    itemsIndexed(artists, key = { _, a -> a.id }) { i, a ->
                        val p = posOf(i, artists.size)
                        GroupCard(p, Modifier.inGroup(p), onClick = { Nav.go(ArtistR(a.id)) }) {
                            Cover(a.thumb, 52.dp, CircleShape)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // the library: downloads, favorites, playlists and history, searched on the phone (works offline)
            type == 4 -> {
                val lib by Library.state.collectAsState()
                val downloads by Downloads.done.collectAsState()
                val phone by Downloads.phone.collectAsState()
                val found = remember(submitted, lib, downloads, phone) {
                    val q = fold(submitted)
                    (downloads + phone + lib.favorites + lib.playlists.flatMap { it.tracks } + lib.history).distinctBy { it.id }
                        .filter { t -> listOf(t.title, t.artist, t.album ?: "").any { q in fold(it) } }
                }
                CompositionLocalProvider(LocalPlayingFrom provides str.library) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                        if (found.isEmpty()) item { Nothing(str.noResults) }
                        trackRows(found)
                    }
                }
            }
            else -> Loader("$submitted|$type|${Musike.lang}", { YouTube.search(submitted, if (type == 0) "song" else "video") }) { tracks ->
                CompositionLocalProvider(LocalPlayingFrom provides "“$submitted”") {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) { trackRows(tracks) }
                }
            }
        }
    }
}

// ---- Genres

@Composable
fun GenresScreen() = Page({ Header(str.genres) }) {
    Loader("genres|${Musike.lang}", { YouTube.genres() }) { list ->
        // the same chips as Search's "explore genres & moods"
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (g in list) Chip(g.name) { Nav.go(GenreR(g.id, g.name)) }
                }
            }
        }
    }
}

@Composable
fun GenreScreen(r: GenreR) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Page({
        Header(r.name, bottom = {
            ConnectedButtons(listOf(str.artists, str.tabSongs), tab, Modifier.padding(horizontal = 16.dp)) { tab = it }
        })
    }) { Loader("${r.id}|${Musike.lang}", { YouTube.genre(r.id) }) { page ->
        var artists by remember { mutableStateOf(page.artists) }
        var more by remember { mutableStateOf(page.hasMoreArtists) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        if (tab == 0) LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = listPadding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(artists, key = { it.id }) { a ->
                Column(Modifier.tap(TileShape, 0.97f) { Nav.go(ArtistR(a.id)) }) {
                    Cover(a.art, 400.dp, TileShape, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                    Text(a.hits.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            if (more) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), Alignment.Center) {
                    if (busy) CircularProgressIndicator()
                    else FilledTonalButton({
                        busy = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { YouTube.genreArtists(r.id, artists.size) } }
                                .onSuccess { (list, has) -> artists = (artists + list).distinctBy { it.id }; more = has }
                                .onFailure { Ui.toast(it.message ?: str.retry) }
                            busy = false
                        }
                    }, shape = PillShape, modifier = Modifier.height(48.dp)) { Text(str.loadMore, style = MaterialTheme.typography.labelLarge) }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item { PlayRow(page.tracks, download = true) }
            trackRows(page.tracks)
        }
    } }
}

// ---- Artist and album

@Composable
fun ArtistScreen(id: String) = Page({
    // over the photo: just a round glass back button
    Box(Modifier.statusBarsPadding().padding(12.dp)) { RoundButton(Icons.AutoMirrored.Filled.ArrowBack, str.back, 44.dp, glass = true) { Nav.back() } }
}) {
    Loader("$id|${Musike.lang}", { YouTube.artist(id) }) { a -> CompositionLocalProvider(LocalPlayingFrom provides a.name) {
        var all by rememberSaveable { mutableStateOf(false) }
        var bio by rememberSaveable { mutableStateOf(false) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current + 16.dp)) {
            item {
                // the artist's photo leads, fading into the page, their name on it
                Box(Modifier.fillMaxWidth().aspectRatio(1.05f)) {
                    AsyncImage(a.banner.ifEmpty { null }, null, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)))
                    Text(a.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp))
                }
            }
            item { PlayRow(a.tracks) }
            if (a.description.isNotEmpty()) item {
                Text(a.description, maxLines = if (bio) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp).tap(MaterialTheme.shapes.medium, 0.99f) { bio = !bio }.padding(12.dp))
            }
            if (a.tracks.isNotEmpty()) {
                item { SectionTitle(str.topSongs, if (a.tracks.size > 5) (if (all) str.showLess else str.showAll) else null) { all = !all } }
                trackRows(a.tracks, limit = if (all) a.tracks.size else 5)
            }
            if (a.albums.isNotEmpty()) { item { SectionTitle(str.albums) }; item { AlbumShelf(a.albums) } }
            if (a.singles.isNotEmpty()) { item { SectionTitle(str.singles) }; item { AlbumShelf(a.singles) } }
        }
    } }
}

@Composable
fun AlbumScreen(id: String) = Page({ Header("") }) {
    Loader("$id|${Musike.lang}", { YouTube.album(id) }) { a -> CompositionLocalProvider(LocalPlayingFrom provides a.title) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(a.art, 240.dp, RoundedCornerShape(28.dp))
                    Text(a.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
                    Text(a.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.tap(PillShape) { a.artistId?.let { Nav.go(ArtistR(it)) } }.padding(horizontal = 10.dp, vertical = 4.dp))
                    Text(listOf(a.kind, a.info).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            item {
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    PlayRow(a.tracks, download = true) {
                        RoundButton(Ic.playlistAdd, str.saveAsPlaylist) { Library.createPlaylist(a.title, a.tracks); Ui.toast(str.saved(a.title)) }
                    }
                }
            }
            trackRows(a.tracks, numbered = true)
        }
    } }
}

// ---- Library

@Composable
fun LibraryScreen() {
    val lib by Library.state.collectAsState()
    val downloads by Downloads.done.collectAsState()
    val phone by Downloads.phone.collectAsState()
    Page({
        Header(str.library, back = false) {
            RoundButton(Icons.Default.Add, str.newPlaylist, 44.dp) { Ui.prompt = Triple(str.newPlaylist, "") { Library.createPlaylist(it) } }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item { GroupRow(Icons.Default.Favorite, str.favorites, Pos.Top, str.songs(lib.favorites.size)) { Nav.go(FavoritesR) } }
            item { GroupRow(Ic.download, str.downloads, Pos.Middle, str.songs(downloads.size)) { Nav.go(DownloadsR) } }
            item { GroupRow(Ic.phone, str.onPhone, Pos.Middle, str.songs(phone.size)) { Nav.go(PhoneR) } }
            item { GroupRow(Ic.history, str.history, Pos.Bottom, str.songs(lib.history.size)) { Nav.go(HistoryR) } }
            item { SectionTitle(str.playlists) }
            // a playlist made from your taste: new songs like the ones you play, like, download and keep
            item {
                // its own card, set apart from the playlists under it
                GroupRow(Ic.auto, str.smartPlaylist, Pos.Single, str.smartPlaylistBody, Modifier.inGroup(Pos.Single).padding(bottom = 12.dp)) { Nav.go(SmartR) }
            }
            if (lib.playlists.isEmpty()) item { Nothing(str.noPlaylists) }
            playlistRows(lib.playlists)
        }
    }
}

/** A smart playlist: what it's made from, which artists, how new, how long; then it's made and opened. */
@Composable
fun SmartScreen() {
    val lib by Library.state.collectAsState()
    val downloads by Downloads.done.collectAsState()
    val phone by Downloads.phone.collectAsState()
    var use by remember { mutableStateOf(Suggest.Source.entries.toSet()) }
    var only by remember { mutableStateOf(emptySet<String>()) }
    var style by remember { mutableIntStateOf(Suggest.Style.Mixed.ordinal) }
    var length by remember { mutableIntStateOf(1) }
    var making by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val artists = remember(use, lib, downloads, phone) { Suggest.artists(lib, downloads, phone, use).take(24) }
    val sources = listOf(
        Triple(Suggest.Source.Favorites, Icons.Default.Favorite, str.favorites to lib.favorites.size),
        Triple(Suggest.Source.Downloads, Ic.download, str.downloads to downloads.size),
        Triple(Suggest.Source.Playlists, Ic.library, str.playlists to lib.playlists.sumOf { it.tracks.size }),
        Triple(Suggest.Source.History, Ic.history, str.history to lib.history.size),
        Triple(Suggest.Source.Phone, Ic.phone, str.onPhone to phone.size),
    )
    val sizes = listOf(20, 30, 50)
    Page({ Header(str.smartPlaylist) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item { Label(str.madeFrom) }
            sources.forEachIndexed { i, (s, icon, info) ->
                item { GroupToggle(icon, info.first, str.songs(info.second), s in use, posOf(i, sources.size)) { use = if (it) use + s else use - s } }
            }
            if (artists.isNotEmpty()) {
                item { Label(str.focusArtists) }
                item {
                    Text(str.focusArtistsBody, Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (a in artists) Chip(a, a in only) { only = if (a in only) only - a else only + a }
                    }
                }
            }
            item { Label(str.style) }
            item {
                ConnectedButtons(str.styles, style, Modifier.padding(horizontal = 16.dp)) { style = it }
                Text(str.styleBodies[style], Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Label(str.length) }
            item { ConnectedButtons(sizes.map { str.songs(it) }, length, Modifier.padding(horizontal = 16.dp)) { length = it } }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 28.dp), Alignment.Center) {
                    if (making) CircularProgressIndicator()
                    else PillButton(str.create, Ic.auto) {
                        if (use.isEmpty()) return@PillButton Ui.toast(str.mixEmpty)
                        making = true
                        scope.launch {
                            // a picked artist that's no longer in the sources doesn't count
                            val focus = only intersect artists.toSet()
                            val tracks = Suggest.mix(lib, downloads, phone, use, focus, Suggest.Style.entries[style], sizes[length])
                            making = false
                            if (tracks.isEmpty()) Ui.toast(str.mixEmpty)
                            else {
                                val name = if (focus.isNotEmpty()) "${str.mix} · " + focus.take(2).joinToString(", ") else Suggest.name(tracks)
                                val id = Library.createPlaylist(name, tracks).id
                                Nav.back()
                                Nav.go(PlaylistR(id))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RAINBOW = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/** Picks any accent: hue, intensity and brightness, the result shown as it'll look. [done] gets "#rrggbb", or null. */
@Composable
private fun ColorPicker(start: String, done: (String?) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(if (start.startsWith("#")) start else "#ff4757"), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val color = Color(rgb)
    @Composable fun Part(label: String, value: Float, range: ClosedFloatingPointRange<Float>, track: Brush, set: (Float) -> Unit) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(PillShape).background(track))
            Slider(value, set, valueRange = range, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
        }
    }
    AlertDialog(
        onDismissRequest = { done(null) },
        title = { Text(str.customColor) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(PillShape).background(color), Alignment.Center) {
                    Text("#%06X".format(rgb and 0xFFFFFF), style = MaterialTheme.typography.titleMedium, color = if (color.luminance() > 0.4f) Color.Black else Color.White)
                }
                Part(str.hue, h, 0f..360f, Brush.horizontalGradient(RAINBOW)) { h = it }
                Part(str.intensity, s, 0f..1f, Brush.horizontalGradient(listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0f, v))), Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)))))) { s = it }
                Part(str.brightness, v, 0.2f..1f, Brush.horizontalGradient(listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, 0.2f))), Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, 1f)))))) { v = it }
            }
        },
        confirmButton = { TextButton({ done("#%06x".format(rgb and 0xFFFFFF)) }) { Text(str.apply) } },
        dismissButton = { TextButton({ done(null) }) { Text(str.cancel) } },
    )
}

/** Favorites, History, Downloads or a playlist. */
@Composable
fun CollectionScreen(r: Route) {
    val lib by Library.state.collectAsState()
    val downloads by Downloads.done.collectAsState()
    val phone by Downloads.phone.collectAsState()
    val ctx = LocalContext.current
    fun granted() = storagePermissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted(); Downloads.refresh() }
    // the phone's music: asked for when first opened, read again each time (songs come and go)
    if (r == PhoneR) LaunchedEffect(Unit) { if (allowed) Downloads.refresh() else ask.launch(storagePermissions) }
    val playlist = (r as? PlaylistR)?.let { pr -> lib.playlists.firstOrNull { it.id == pr.id } }
    val (title, tracks) = when (r) {
        FavoritesR -> str.favorites to lib.favorites
        HistoryR -> str.history to lib.history
        DownloadsR -> str.downloads to downloads
        PhoneR -> str.onPhone to phone
        else -> (playlist?.name ?: "") to (playlist?.tracks ?: emptyList())
    }
    var menu by remember { mutableStateOf(false) }
    Page({
        Header(title, "${str.songs(tracks.size)} • ${str.total(tracks.sumOf { it.duration })}") {
            if (playlist != null) Box {
                RoundButton(Icons.Default.MoreVert, str.more, 44.dp) { menu = true }
                DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
                    DropdownMenuItem({ Text(str.addSongs) }, { menu = false; Ui.addSongsTo = playlist.id }, leadingIcon = { Icon(Icons.Default.Add, null) })
                    DropdownMenuItem({ Text(str.rename) }, { menu = false; Ui.prompt = Triple(str.rename, playlist.name) { Library.renamePlaylist(playlist.id, it) } },
                        leadingIcon = { Icon(Icons.Default.Edit, null) })
                    DropdownMenuItem({ Text(str.delete) }, { menu = false; Ui.confirm = str.deletePlaylist(playlist.name) to { Nav.back(); Library.deletePlaylist(playlist.id) } },
                        leadingIcon = { Icon(Icons.Default.Delete, null) })
                }
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item { PlayRow(tracks, download = r != DownloadsR && r != PhoneR) }
            if (tracks.isEmpty() && playlist != null) item {
                // an empty playlist: the way to fill it, right there
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Nothing(str.emptyPlaylist)
                    PillButton(str.addSongs, Icons.Default.Add) { Ui.addSongsTo = playlist.id }
                }
            } else if (r == PhoneR && !allowed) item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Nothing(str.phoneAllow)
                    PillButton(str.allow, Ic.phone) { ask.launch(storagePermissions) }
                }
            } else if (tracks.isEmpty()) item { Nothing(when (r) { DownloadsR -> str.downloadsEmpty; PhoneR -> str.phoneEmpty; else -> str.empty }) }
            trackRows(tracks, playlistId = playlist?.id ?: HISTORY.takeIf { r == HistoryR })
        }
    }
}

// ---- Settings

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    var pending by remember { mutableStateOf<LibraryData?>(null) }
    var picking by remember { mutableStateOf(false) }
    if (picking) ColorPicker(Musike.accent) { picking = false; if (it != null) Musike.set("accent", it) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Library.backup(version).toByteArray()) } }
            .onSuccess { Ui.toast(str.exported) }.onFailure { Ui.toast(it.message ?: "") }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { Library.readBackup(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) }
            .onSuccess { pending = it }.onFailure { Ui.toast(it.message ?: str.notBackup) }
    }
    Page({ Header(str.settings) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item { Label(str.language) }
            itemsIndexed(LANGUAGES, key = { _, l -> l.first }) { i, (code, name) ->
                GroupSelect(Ic.language, name, Musike.lang == code, posOf(i, LANGUAGES.size)) { Musike.set("lang", code) }
            }
            item { Label(str.theme) }
            item {
                val modes = listOf("system", "dark", "light")
                ConnectedButtons(listOf(str.themeSystem, str.themeDark, str.themeLight), modes.indexOf(Musike.theme), Modifier.padding(horizontal = 16.dp)) { Musike.set("theme", modes[it]) }
            }
            item { Label(str.accent) }
            item {
                FlowRow(
                    Modifier.inGroup(Pos.Single).clip(groupShape(Pos.Single)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (c in ACCENTS) {
                        val color = hex(c)
                        val on = Musike.accent == c
                        Box(Modifier.size(44.dp).tap(CircleShape, 0.88f) { Musike.set("accent", c) }.background(color)
                            .border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape), Alignment.Center) {
                            if (on) Icon(Icons.Default.Check, null, tint = if (color.luminance() > 0.4f) Color.Black else Color.White)
                        }
                    }
                    // any colour: a rainbow until one is picked, then that colour
                    val custom = Musike.accent != "dynamic" && Musike.accent !in ACCENTS
                    Box(Modifier.size(44.dp).tap(CircleShape, 0.88f) { picking = true }
                        .background(if (custom) SolidColor(hex(Musike.accent)) else Brush.sweepGradient(RAINBOW))
                        .border(if (custom) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape), Alignment.Center) {
                        if (custom) Icon(Icons.Default.Check, null, tint = if (hex(Musike.accent).luminance() > 0.4f) Color.Black else Color.White)
                        else Icon(Icons.Default.Add, str.customColor, tint = Color.White)
                    }
                    if (Musike.dynamicAvailable) FilterChip(Musike.accent == "dynamic", { Musike.set("accent", "dynamic") }, { Text(str.wallpaper) },
                        Modifier.height(44.dp), shape = PillShape)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item { GroupToggle(Ic.radio, str.autoplay, str.autoplayBody, Musike.autoRadio, if (Musike.glassCapable) Pos.Top else Pos.Single) { Musike.set("autoRadio", it) } }
            // phones that can't blur (or spare the memory) don't get the choice
            if (Musike.glassCapable) item { GroupToggle(Ic.explore, str.glass, str.glassBody, Musike.glass, Pos.Bottom) { Musike.set("glass", it) } }
            item { Label(str.backup) }
            item {
                GroupRow(Icons.Default.Share, str.exportLib, Pos.Top, str.exportBody) {
                    export.launch("medify-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json")
                }
            }
            item { GroupRow(Ic.download, str.importLib, Pos.Bottom, str.importBody) { import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) } }
            item { Label(str.about) }
            item { GroupRow(Ic.info, str.version(version), Pos.Single, trailing = null) {} }
        }
    }
    pending?.let { b ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(str.importLib) },
            text = { Text(str.importQ(b.playlists.size, b.favorites.size), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton({ Library.import(b, replace = false); pending = null; Ui.toast(str.imported) }) { Text(str.merge) } },
            dismissButton = { TextButton({ Library.import(b, replace = true); pending = null; Ui.toast(str.imported) }) { Text(str.replace) } },
        )
    }
}
