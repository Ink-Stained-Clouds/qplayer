package dev.t1m3.qplayer.netease;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the api-enhanced endpoint/transport mapping used by QPlayer. */
public class NeteaseApiTest {

    @Test
    public void searchUsesCurrentCloudSearchDefinition() {
        assertEndpoint(NeteaseApi.CLOUD_SEARCH,
                "/api/cloudsearch/pc", NeteaseApi.Transport.EAPI);
        assertFalse(NeteaseApi.CLOUD_SEARCH.checkToken);
    }

    @Test
    public void catalogMatchesEnhancedTransportDefinitions() {
        assertEndpoint(NeteaseApi.SONG_URL_V1,
                "/api/song/enhance/player/url/v1", NeteaseApi.Transport.XEAPI);
        assertEndpoint(NeteaseApi.HOT_SEARCH_DETAIL,
                "/api/hotsearchlist/get", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.PERSONALIZED_PLAYLIST,
                "/api/personalized/playlist", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.PLAYLIST_DETAIL,
                "/api/v6/playlist/detail", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.SONG_DETAIL,
                "/api/v3/song/detail", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.LYRIC_NEW,
                "/api/song/lyric/v1", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.PLAYLIST_TRACKS,
                "/api/playlist/manipulate/tracks", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LIKE_LIST,
                "/api/song/like/get", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.RECOMMEND_SONGS,
                "/api/v3/discovery/recommend/songs", NeteaseApi.Transport.WEAPI);
        assertTrue(NeteaseApi.PLAYLIST_SUBSCRIBE.checkToken);
        assertTrue(NeteaseApi.PLAYLIST_UNSUBSCRIBE.checkToken);
        assertTrue(NeteaseApi.QR_LOGIN_KEY.loginFlow);
        assertTrue(NeteaseApi.QR_LOGIN_CHECK.loginFlow);
        assertEquals("/api/v1/user/detail/1234", NeteaseApi.userDetail(1234L).path);
    }

    private static void assertEndpoint(NeteaseApi.Endpoint endpoint, String path,
            NeteaseApi.Transport transport) {
        assertEquals(path, endpoint.path);
        assertEquals(transport, endpoint.transport);
    }
}
