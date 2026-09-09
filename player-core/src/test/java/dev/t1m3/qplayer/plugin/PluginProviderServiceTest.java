package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.Song;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class PluginProviderServiceTest {

    private static Map<String, Object> named(String id, String name) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", name);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Playlist parsePlaylist(Map<String, Object> playlist) throws Exception {
        // Artwork urls are left out so the parse never reaches the host api.
        PluginProviderService service = new PluginProviderService(null, null);
        Method parse = PluginProviderService.class.getDeclaredMethod(
                "parsePlaylist", String.class, Map.class);
        parse.setAccessible(true);
        return (Playlist) parse.invoke(service, "netease", playlist);
    }

    /** A real catalog answers with nameless credits (cloud-disk uploads, delisted
     *  artists). Rejecting the response made the whole playlist unopenable. */
    @Test public void namelessCreditsAreDroppedInsteadOfFailingThePlaylist() throws Exception {
        List<Object> artists = new ArrayList<>();
        artists.add(named("1", ""));
        artists.add(named("2", "有名字的歌手"));
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("id", "42");
        song.put("title", "歌曲");
        song.put("artists", artists);
        song.put("album", named("7", ""));
        List<Object> songs = new ArrayList<>();
        songs.add(song);
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("id", "9");
        playlist.put("name", "歌单");
        playlist.put("songs", songs);

        Playlist parsed = parsePlaylist(playlist);

        assertEquals(1, parsed.songs.size());
        Song only = parsed.songs.get(0);
        assertEquals(1, only.artists.size());
        assertEquals("有名字的歌手", only.artists.get(0).name);
        assertEquals("netease:artist:2", only.artists.get(0).id);
        assertNull("an unnamed album reference stays unset", only.album);
    }

    /** List rows draw at ~48dp; without a small variant every visible row pulled
     *  and decoded the full-size cover while scrolling. */
    @Test public void aRowThumbFallsBackToTheFullArtwork() throws Exception {
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("id", "42");
        song.put("title", "歌曲");
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("id", "9");
        playlist.put("name", "歌单");
        List<Object> songs = new ArrayList<>();
        songs.add(song);
        playlist.put("songs", songs);

        Song parsed = parsePlaylist(playlist).songs.get(0);

        assertEquals(parsed.artworkUrl, parsed.coverThumbPath);
    }
}
