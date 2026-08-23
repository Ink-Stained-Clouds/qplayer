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

    private NeteaseApi() {}
}
