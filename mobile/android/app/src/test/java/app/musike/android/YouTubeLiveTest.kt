package app.musike.android

import org.junit.Assert.assertTrue
import org.junit.Test

/** Live check against YouTube Music: run after a parsing change, since YouTube changes its responses. ./gradlew testDebugUnitTest */
class YouTubeLiveTest {
    @Test fun searchAndBrowse() {
        val songs = YouTube.search("radiohead creep", "song")
        assertTrue("songs: $songs", songs.size > 3 && songs.first().artist.isNotEmpty() && songs.first().duration > 0 && songs.first().kind == "song")
        assertTrue(YouTube.search("radiohead creep", "video").first().kind == "video")
        assertTrue(YouTube.suggestions("radioh").isNotEmpty())
        assertTrue(YouTube.searchArtists("radiohead").first().name == "Radiohead")
        val albums = YouTube.searchAlbums("radiohead")
        assertTrue("albums: $albums", albums.isNotEmpty() && albums.first().year.isNotEmpty())
        val album = YouTube.album(albums.first().id)
        assertTrue("album: $album", album.tracks.size > 3 && album.artistId != null && album.art.isNotEmpty())
        assertTrue("album track artist: ${album.tracks.first()}", album.tracks.all { it.artist == album.artist })
        val artist = YouTube.artist(album.artistId!!)
        assertTrue("artist: ${artist.name} ${artist.tracks.size} ${artist.albums.size} ${artist.singles.size}", artist.tracks.size > 10 && artist.albums.size > 5 && artist.singles.isNotEmpty())
        assertTrue(YouTube.radio(songs.first().id).size > 10)
        val genres = YouTube.genres()
        assertTrue("genres: $genres", genres.size > 10)
        val genre = YouTube.genre(genres.first().id)
        assertTrue("genre: ${genre.tracks.size} ${genre.artists.size}", genre.tracks.size > 20 && genre.artists.size > 10)
        println("ok: ${songs.size} songs, ${album.title} (${album.tracks.size}), ${artist.name}: ${artist.tracks.size}/${artist.albums.size}/${artist.singles.size}, ${genres.size} genres, ${genre.name}: ${genre.artists.size} artists ${genre.tracks.size} tracks")
    }
}
