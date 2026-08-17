package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class PlayerControllerPlaybackTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void selectingTrackAfterSessionRestoreDoesNotReplayItOnResume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("state").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":42000,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"restored\",\"durationMs\":120000,\"filePath\":\"restored.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"selected\",\"durationMs\":120000,\"filePath\":\"selected.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);

            // Choosing a queue entry is a real playAt() before the restored entry's
            // play button has consumed needsReplay.
            controller.playQueueIndex(1);
            assertEquals(1, backend.playCalls);

            controller.toggle(); // pause
            controller.toggle(); // resume must use the already-loaded backend

            assertEquals(1, backend.playCalls);
            assertEquals(1, backend.resumeCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static final class FakeAudioBackend implements AudioBackend {
        int playCalls;
        int resumeCalls;
        boolean playing;
        long position;

        @Override public void play(String source, long startMs) {
            playCalls++;
            position = startMs;
            playing = true;
        }

        @Override public void pause() { playing = false; }

        @Override public void resume() {
            resumeCalls++;
            playing = true;
        }

        @Override public boolean isPlaying() { return playing; }
        @Override public void seek(long ms) { position = ms; }
        @Override public long position() { return position; }
        @Override public long duration() { return 120000L; }
        @Override public void setVolume(float volume) { }
        @Override public void setOnComplete(Runnable callback) { }
        @Override public void release() { playing = false; }
    }
}
