package dev.t1m3.qplayer.netease.dto;

/**
 * Minimal song descriptor decoded from netease search / playlist /
 * recommend responses. Keep this a flat POJO so the QML bridge can read its
 * fields directly.
 */
public class NeteaseSong {
    public long id;
    public String name;
    /** All artists joined with " / ". Null if absent. */
    public String artist;
    /** Id of the first-listed artist -- lets the UI open that artist's page. 0 if absent. */
    public long artistId;
    public String album;
    /** Id of the album -- lets the UI open the album page. 0 if absent. */
    public long albumId;
    /** Album cover URL (CDN, jpg/png). Renderer fetches bytes lazily. */
    public String coverUrl;
    /** CDN thumbnail URL (coverUrl + ?param=128y128) for QML Image.source.
     *  Set by the controller after a search completes; null until resolved. */
    public String coverThumbPath;
    /** Track length in milliseconds (field "dt" in the JSON). */
    public long durationMs;
    /** Set when the song is VIP / unavailable to anonymous clients. */
    public boolean fee;
    /** Whether this song's audio is already on disk ({@code DiskCache.hasAudio}) --
     *  i.e. playable with no network. Set by whoever builds the list (openPlaylist,
     *  offlinePlaylistFallback); QML uses it to badge "offline-ready" tracks while
     *  {@code player.playlistOffline} is true. Not persisted -- computed fresh
     *  every time a song list is built, since the cache itself can change. */
    public boolean cachedOffline;
}
