package dev.t1m3.qplayer.netease;

/**
 * Central catalogue for every NetEase endpoint used by QPlayer.
 *
 * <p>Paths and transports mirror the corresponding modules in
 * NeteaseCloudMusicApiEnhanced/api-enhanced. Feature code must reference an
 * endpoint here instead of choosing a raw path or encryption scheme itself.
 */
final class NeteaseApi {

    enum Transport {
        WEAPI,
        EAPI,
        XEAPI,
        DIRECT
    }

    static final class Endpoint {
        final String path;
        final Transport transport;
        final boolean checkToken;
        final boolean loginFlow;

        private Endpoint(String path, Transport transport, boolean checkToken, boolean loginFlow) {
            if (path == null || !path.startsWith("/api/")) {
                throw new IllegalArgumentException("canonical API path must start with /api/: " + path);
            }
            this.path = path;
            this.transport = transport;
            this.checkToken = checkToken;
            this.loginFlow = loginFlow;
        }

        String weapiPath() {
            if (transport != Transport.WEAPI) {
                throw new IllegalStateException(path + " is not a weapi endpoint");
            }
            return path.substring("/api/".length());
        }
    }

    private static Endpoint endpoint(String path, Transport transport) {
        return new Endpoint(path, transport, false, false);
    }

    private static Endpoint checked(String path) {
        return new Endpoint(path, Transport.EAPI, true, false);
    }

    private static Endpoint login(String path, Transport transport) {
        return new Endpoint(path, transport, false, true);
    }

    static final Endpoint SONG_URL_V1 = endpoint(
            "/api/song/enhance/player/url/v1", Transport.XEAPI);
    static final Endpoint HOT_SEARCH_DETAIL = endpoint(
            "/api/hotsearchlist/get", Transport.WEAPI);
    static final Endpoint PERSONALIZED_PLAYLIST = endpoint(
            "/api/personalized/playlist", Transport.WEAPI);
    static final Endpoint CLOUD_SEARCH = endpoint(
            "/api/cloudsearch/pc", Transport.EAPI);
    static final Endpoint PLAYLIST_DETAIL = endpoint(
            "/api/v6/playlist/detail", Transport.EAPI);
    static final Endpoint SONG_DETAIL = endpoint(
            "/api/v3/song/detail", Transport.WEAPI);
    static final Endpoint PLAYLIST_SUBSCRIBE = checked(
            "/api/playlist/subscribe");
    static final Endpoint PLAYLIST_UNSUBSCRIBE = checked(
            "/api/playlist/unsubscribe");
    static final Endpoint LOGIN_STATUS = endpoint(
            "/api/w/nuser/account/get", Transport.WEAPI);
    static final Endpoint USER_PLAYLIST = endpoint(
            "/api/user/playlist", Transport.WEAPI);
    static final Endpoint RECENT_SONGS = endpoint(
            "/api/play-record/song/list", Transport.WEAPI);
    static final Endpoint WEBLOG = endpoint(
            "/api/feedback/weblog", Transport.WEAPI);
    static final Endpoint LYRIC_NEW = endpoint(
            "/api/song/lyric/v1", Transport.EAPI);
    static final Endpoint LIKE = endpoint(
            "/api/radio/like", Transport.WEAPI);
    static final Endpoint PLAYLIST_TRACKS = endpoint(
            "/api/playlist/manipulate/tracks", Transport.EAPI);
    static final Endpoint PLAYLIST_CREATE = endpoint(
            "/api/playlist/create", Transport.WEAPI);
    static final Endpoint PLAYLIST_DELETE = endpoint(
            "/api/playlist/remove", Transport.WEAPI);
    static final Endpoint NOS_TOKEN_ALLOC = endpoint(
            "/api/nos/token/alloc", Transport.WEAPI);
    static final Endpoint PLAYLIST_COVER_UPDATE = endpoint(
            "/api/playlist/cover/update", Transport.WEAPI);
    static final Endpoint LIKE_LIST = endpoint(
            "/api/song/like/get", Transport.EAPI);
    static final Endpoint RECOMMEND_SONGS = endpoint(
            "/api/v3/discovery/recommend/songs", Transport.WEAPI);
    static final Endpoint PLAYMODE_INTELLIGENCE_LIST = endpoint(
            "/api/playmode/intelligence/list", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_ROOM_CREATE = endpoint(
            "/api/listen/together/room/create", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_STATUS = endpoint(
            "/api/listen/together/status/get", Transport.WEAPI);
    static final Endpoint LISTEN_TOGETHER_ROOM_CHECK = endpoint(
            "/api/listen/together/room/check", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_INVITATION_ACCEPT = endpoint(
            "/api/listen/together/play/invitation/accept", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_SYNC_PLAYLIST_GET = endpoint(
            "/api/listen/together/sync/playlist/get", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_SYNC_LIST_REPORT = endpoint(
            "/api/listen/together/sync/list/command/report", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_PLAY_COMMAND_REPORT = endpoint(
            "/api/listen/together/play/command/report", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_HEARTBEAT = endpoint(
            "/api/listen/together/heartbeat", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_END = endpoint(
            "/api/listen/together/end/v2", Transport.EAPI);
    // Multi-person "一起听" (LTMulti) family — a distinct, newer feature from the
    // classic two-person LISTEN_TOGETHER_* endpoints above, reverse-engineered
    // from the official Android client (v9.5.81) since no community reference
    // documents it. Paths/body fields below are transcribed from decompiled
    // request-builder code, not guessed.
    static final Endpoint LISTEN_TOGETHER_MULTI_ROOM_CREATE = endpoint(
            "/api/listen/together/multi/room/create", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_MULTI_INVITE = endpoint(
            "/api/listen/together/multi/invite", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_MULTI_MATCH_HEARTBEAT = endpoint(
            "/api/listen/together/multi/match/heartbeat", Transport.EAPI);
    static final Endpoint LISTEN_TOGETHER_MULTI_MATCH_EXIT = endpoint(
            "/api/listen/together/multi/match/exit", Transport.EAPI);
    static final Endpoint LOGOUT = login(
            "/api/logout", Transport.EAPI);
    static final Endpoint QR_LOGIN_KEY = login(
            "/api/login/qrcode/unikey", Transport.EAPI);
    static final Endpoint QR_LOGIN_CHECK = login(
            "/api/login/qrcode/client/login", Transport.EAPI);
    static final Endpoint XEAPI_SECURITY_KEY = endpoint(
            "/api/gorilla/anti/crawler/security/key/get", Transport.DIRECT);

    static Endpoint userDetail(long uid) {
        return endpoint("/api/v1/user/detail/" + uid, Transport.WEAPI);
    }

    static Endpoint artistDetail(long artistId) {
        return endpoint("/api/v1/artist/" + artistId, Transport.WEAPI);
    }

    static Endpoint artistAlbums(long artistId) {
        return endpoint("/api/artist/albums/" + artistId, Transport.WEAPI);
    }

    static Endpoint albumDetail(long albumId) {
        return endpoint("/api/v1/album/" + albumId, Transport.WEAPI);
    }

    private NeteaseApi() {}
}
