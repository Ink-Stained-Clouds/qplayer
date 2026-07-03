package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.store.AppDirs;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Operation-simulation tests for PlayerController.
 * Tests search history management, playlist subscribe state, and queue operations.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayerControllerTest {

    @TempDir
    Path tempDir;

    @Mock AudioBackend backend;
    @Mock MetadataReader metadataReader;
    @Mock NeteaseClient netease;

    private PlayerController ctrl;

    @BeforeEach
    void setUp() throws Exception {
        AppDirs.setBase(tempDir.toString());
        // Stub playlist tracks to avoid NPE in fillMissingCovers when worker runs
        when(netease.playlistTracks(anyLong(), anyInt())).thenReturn(Collections.<NeteaseSong>emptyList());
        ctrl = new PlayerController(backend, metadataReader, netease);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Give worker thread time to finish pending file I/O before TempDir cleanup
        Thread.sleep(150);
        ctrl.shutdown();
    }

    // -------------------------------------------------------------------------
    // Search history
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addSearchHistory: 新关键词加到列表最前")
    void searchHistory_addAppearsFirst() {
        ctrl.addSearchHistory("周杰伦");
        ctrl.pump();
        ctrl.addSearchHistory("林俊杰");
        ctrl.pump();

        List<String> h = ctrl.searchHistory.peek();
        assertEquals("林俊杰", h.get(0), "最近添加的关键词应在首位");
        assertEquals("周杰伦", h.get(1));
        assertEquals(2, h.size());
    }

    @Test
    @DisplayName("addSearchHistory: 重复关键词移到最前（不增加条数）")
    void searchHistory_duplicateMovesToFront() {
        ctrl.addSearchHistory("周杰伦");
        ctrl.pump();
        ctrl.addSearchHistory("林俊杰");
        ctrl.pump();
        ctrl.addSearchHistory("周杰伦"); // duplicate
        ctrl.pump();

        List<String> h = ctrl.searchHistory.peek();
        assertEquals("周杰伦", h.get(0));
        assertEquals(2, h.size(), "重复条目不应增加列表长度");
    }

    @Test
    @DisplayName("addSearchHistory: 空字符串和 null 忽略")
    void searchHistory_ignoresBlank() {
        ctrl.addSearchHistory("");
        ctrl.addSearchHistory("  ");
        ctrl.addSearchHistory(null);
        ctrl.pump();

        assertTrue(ctrl.searchHistory.peek().isEmpty());
    }

    @Test
    @DisplayName("addSearchHistory: 超过50条时只保留最新50条")
    void searchHistory_capsAt50() {
        for (int i = 1; i <= 55; i++) {
            ctrl.addSearchHistory("kw" + i);
        }
        ctrl.pump();

        List<String> h = ctrl.searchHistory.peek();
        assertEquals(50, h.size(), "历史记录最多50条");
        assertEquals("kw55", h.get(0), "最新的在最前");
        assertFalse(h.contains("kw1"), "最老的应被淘汰");
        assertFalse(h.contains("kw5"), "超出部分应被淘汰");
    }

    @Test
    @DisplayName("removeSearchHistory: 按索引删除指定条目")
    void searchHistory_removeByIndex() {
        ctrl.addSearchHistory("A");
        ctrl.addSearchHistory("B");
        ctrl.addSearchHistory("C"); // list after pump: [C, B, A]
        ctrl.pump();

        ctrl.removeSearchHistory(1); // remove "B"
        ctrl.pump();

        List<String> h = ctrl.searchHistory.peek();
        assertEquals(2, h.size());
        assertEquals("C", h.get(0));
        assertEquals("A", h.get(1));
    }

    @Test
    @DisplayName("removeSearchHistory: 越界索引无操作")
    void searchHistory_removeOutOfBounds() {
        ctrl.addSearchHistory("A");
        ctrl.pump();

        ctrl.removeSearchHistory(-1);
        ctrl.removeSearchHistory(99);
        ctrl.pump();

        assertEquals(1, ctrl.searchHistory.peek().size(), "越界删除不应改变列表");
    }

    @Test
    @DisplayName("clearSearchHistory: 清空所有历史")
    void searchHistory_clear() {
        ctrl.addSearchHistory("A");
        ctrl.addSearchHistory("B");
        ctrl.addSearchHistory("C");
        ctrl.pump();

        ctrl.clearSearchHistory();
        ctrl.pump();

        assertTrue(ctrl.searchHistory.peek().isEmpty(), "清空后列表应为空");
    }

    @Test
    @DisplayName("搜索历史持久化：保存后重新加载可恢复")
    void searchHistory_persistsAcrossRestart() throws Exception {
        ctrl.addSearchHistory("周杰伦");
        ctrl.addSearchHistory("林俊杰");
        ctrl.addSearchHistory("邓紫棋");
        ctrl.pump();

        // 等待 worker 线程将文件写入磁盘
        Thread.sleep(150);
        ctrl.shutdown();

        // 重新创建 controller 模拟应用重启
        ctrl = new PlayerController(backend, metadataReader, netease);
        Thread.sleep(150); // 等待 worker 加载历史
        ctrl.pump();

        List<String> h = ctrl.searchHistory.peek();
        assertTrue(h.contains("周杰伦"), "重启后周杰伦应在历史中");
        assertTrue(h.contains("林俊杰"), "重启后林俊杰应在历史中");
        assertTrue(h.contains("邓紫棋"), "重启后邓紫棋应在历史中");
        assertEquals("邓紫棋", h.get(0), "最近添加的应在最前");
    }

    // -------------------------------------------------------------------------
    // Queue operations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addSearchResultToQueue: 将搜索结果加入播放列表")
    void queue_addSearchResult() {
        NeteaseSong song = makeSong(12345L, "稻香", "周杰伦");
        ctrl.searchResults.set(Arrays.asList(song));

        int before = ctrl.queueTracks.peek().size();
        ctrl.addSearchResultToQueue(0);

        assertEquals(before + 1, ctrl.queueTracks.peek().size(), "队列应增加一首歌");
        assertEquals("已加入播放列表", ctrl.toast.peek(), "应显示成功提示");
    }

    @Test
    @DisplayName("addSearchResultToQueue: 越界索引无操作")
    void queue_addSearchResultOutOfBounds() {
        ctrl.searchResults.set(Collections.<NeteaseSong>emptyList());

        ctrl.addSearchResultToQueue(0);
        ctrl.addSearchResultToQueue(-1);

        assertEquals(0, ctrl.queueTracks.peek().size(), "空结果集操作不应改变队列");
    }

    @Test
    @DisplayName("addRecentSongToQueue: 将最近播放歌曲加入队列")
    void queue_addRecentSong() {
        NeteaseSong song = makeSong(99L, "青花瓷", "周杰伦");
        ctrl.recentSongs.set(Arrays.asList(song));

        ctrl.addRecentSongToQueue(0);

        assertEquals(1, ctrl.queueTracks.peek().size());
        assertEquals("已加入播放列表", ctrl.toast.peek());
    }

    @Test
    @DisplayName("addPlaylistTrackToQueue: 将歌单曲目加入队列")
    void queue_addPlaylistTrack() {
        NeteaseSong song = makeSong(777L, "倒带", "蔡依林");
        ctrl.playlistTracks.set(Arrays.asList(song));

        ctrl.addPlaylistTrackToQueue(0);

        assertEquals(1, ctrl.queueTracks.peek().size());
    }

    @Test
    @DisplayName("多次加队列：每首歌都被添加（含重复）")
    void queue_addMultipleSongs() {
        NeteaseSong a = makeSong(1L, "A", "ArtistA");
        NeteaseSong b = makeSong(2L, "B", "ArtistB");
        ctrl.searchResults.set(Arrays.asList(a, b));

        ctrl.addSearchResultToQueue(0);
        ctrl.addSearchResultToQueue(1);
        ctrl.addSearchResultToQueue(0); // add A again

        assertEquals(3, ctrl.queueTracks.peek().size(), "队列允许重复添加");
    }

    // -------------------------------------------------------------------------
    // Playlist subscribe state (both alias sets)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("openPlaylist: 加载开始时两套别名均重置为 false")
    void playlist_openResetsAllAliases() throws Exception {
        // 先手动设为 true
        ctrl.playlistSubscribed.set(true);
        ctrl.playlistOwned.set(true);
        ctrl.currentPlaylistSubscribed.set(true);
        ctrl.currentPlaylistIsOwn.set(true);

        // 重置在 worker 提交之前同步发生，无需等待 worker
        ctrl.openPlaylist(42L);

        assertFalse(ctrl.playlistSubscribed.peek(),        "playlistSubscribed 应重置");
        assertFalse(ctrl.playlistOwned.peek(),             "playlistOwned 应重置");
        assertFalse(ctrl.currentPlaylistSubscribed.peek(), "currentPlaylistSubscribed 应重置");
        assertFalse(ctrl.currentPlaylistIsOwn.peek(),      "currentPlaylistIsOwn 应重置");
    }

    @Test
    @DisplayName("openPlaylist: 加载完成后两套别名均反映实际 subscribed 状态")
    void playlist_openSyncsBothAliases() throws Exception {
        NeteasePlaylist detail = makePlaylist(42L, "已收藏歌单", true, 9999L);
        when(netease.playlistDetail(42L)).thenReturn(detail);

        ctrl.openPlaylist(42L);
        Thread.sleep(150);
        ctrl.pump();

        assertTrue(ctrl.playlistSubscribed.peek(),        "playlistSubscribed 应为 true");
        assertTrue(ctrl.currentPlaylistSubscribed.peek(), "currentPlaylistSubscribed 应为 true");
        assertFalse(ctrl.playlistOwned.peek(),            "未拥有的歌单 playlistOwned 应为 false");
        assertFalse(ctrl.currentPlaylistIsOwn.peek(),     "currentPlaylistIsOwn 应为 false");
    }

    @Test
    @DisplayName("togglePlaylistSubscribe: 两套别名乐观翻转后保持同步")
    void playlist_toggleSyncsBothAliases() throws Exception {
        NeteasePlaylist detail = makePlaylist(10L, "别人的歌单", false, 8888L);
        when(netease.playlistDetail(10L)).thenReturn(detail);
        when(netease.playlistSubscribe(anyLong(), anyBoolean())).thenReturn(true);

        ctrl.loggedIn.set(true);
        ctrl.openPlaylist(10L);
        Thread.sleep(150);
        ctrl.pump();

        assertFalse(ctrl.playlistSubscribed.peek(), "初始未收藏");

        // 乐观翻转是同步的
        ctrl.togglePlaylistSubscribe();
        assertTrue(ctrl.playlistSubscribed.peek(),        "playlistSubscribed 应乐观翻转为 true");
        assertTrue(ctrl.currentPlaylistSubscribed.peek(), "currentPlaylistSubscribed 应同步翻转");

        Thread.sleep(150);
        ctrl.pump();

        assertTrue(ctrl.playlistSubscribed.peek(),        "服务端成功后应保持 true");
        assertTrue(ctrl.currentPlaylistSubscribed.peek(), "currentPlaylistSubscribed 应保持 true");
    }

    @Test
    @DisplayName("togglePlaylistSubscribe: 服务端失败时两套别名均回滚")
    void playlist_toggleRevertsOnFailure() throws Exception {
        NeteasePlaylist detail = makePlaylist(10L, "别人的歌单", false, 8888L);
        when(netease.playlistDetail(10L)).thenReturn(detail);
        when(netease.playlistSubscribe(anyLong(), anyBoolean())).thenReturn(false);

        ctrl.loggedIn.set(true);
        ctrl.openPlaylist(10L);
        Thread.sleep(150);
        ctrl.pump();

        ctrl.togglePlaylistSubscribe();
        assertTrue(ctrl.playlistSubscribed.peek(), "乐观翻转后应为 true");

        Thread.sleep(150);
        ctrl.pump();

        assertFalse(ctrl.playlistSubscribed.peek(),        "服务端失败后 playlistSubscribed 应回滚");
        assertFalse(ctrl.currentPlaylistSubscribed.peek(), "currentPlaylistSubscribed 应同步回滚");
    }

    @Test
    @DisplayName("togglePlaylistSubscribe: 未登录时无操作")
    void playlist_toggleNoOpWhenNotLoggedIn() throws Exception {
        NeteasePlaylist detail = makePlaylist(10L, "别人的歌单", false, 8888L);
        when(netease.playlistDetail(10L)).thenReturn(detail);

        // loggedIn 保持 false
        ctrl.openPlaylist(10L);
        Thread.sleep(150);
        ctrl.pump();

        ctrl.togglePlaylistSubscribe();

        assertFalse(ctrl.playlistSubscribed.peek(), "未登录时不应改变 playlistSubscribed");
        verify(netease, never()).playlistSubscribe(anyLong(), anyBoolean());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static NeteaseSong makeSong(long id, String name, String artist) {
        NeteaseSong s = new NeteaseSong();
        s.id = id;
        s.name = name;
        s.artist = artist;
        s.album = "album";
        s.coverUrl = "";
        s.durationMs = 180000;
        return s;
    }

    private static NeteasePlaylist makePlaylist(long id, String name, boolean subscribed, long creatorUid) {
        NeteasePlaylist p = new NeteasePlaylist();
        p.id = id;
        p.name = name;
        p.subscribed = subscribed;
        p.creatorUid = creatorUid;
        p.coverUrl = "";
        return p;
    }
}
