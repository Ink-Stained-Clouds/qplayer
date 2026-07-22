package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches a local music-library folder tree for filesystem changes and triggers
 * a debounced rescan, so adding/removing/editing files under {@code musicFolder}
 * is picked up automatically instead of requiring the user to re-touch Settings
 * or restart the app. LibraryScanner's per-file cache already makes a rescan
 * itself cheap (an unchanged file skips the expensive tag/cover/lyric re-read),
 * so this deliberately just re-triggers a full {@code scan()} on any change
 * rather than diffing individual files itself.
 */
final class LibraryWatcher {

    private static final long DEBOUNCE_MS = 1000;

    private final Runnable onChange;
    private final Map<WatchKey, Path> watchedDirs = new ConcurrentHashMap<>();
    private final Timer debounceTimer = new Timer("qplayer-library-watch-debounce", true);

    private WatchService service;
    private Thread loopThread;
    private volatile boolean stopped = true;
    private TimerTask pendingRescan;

    LibraryWatcher(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Start watching {@code root} recursively; safe to call again (e.g. the user
     *  changed musicFolder in Settings) — replaces any previous watch. No-op if
     *  root isn't a real directory. */
    synchronized void start(String root) {
        stop();
        if (root == null || root.isEmpty()) return;
        Path rootPath = Paths.get(root);
        if (!Files.isDirectory(rootPath)) return;
        try {
            service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            Logger.warn("LibraryWatcher: newWatchService failed: {}", e.getMessage());
            return;
        }
        stopped = false;
        try (java.util.stream.Stream<Path> walk = Files.walk(rootPath)) {
            walk.filter(Files::isDirectory).forEach(this::registerDir);
        } catch (IOException e) {
            Logger.warn("LibraryWatcher: initial registration failed: {}", e.getMessage());
        }
        loopThread = new Thread(this::loop, "qplayer-library-watch");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    synchronized void stop() {
        stopped = true;
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
            }
            service = null;
        }
        watchedDirs.clear();
        loopThread = null; // the loop thread exits on its own once the service is closed
    }

    private void registerDir(Path dir) {
        try {
            WatchKey key = dir.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            watchedDirs.put(key, dir);
        } catch (IOException e) {
            Logger.warn("LibraryWatcher: register failed for {}: {}", dir, e.getMessage());
        }
    }

    private void loop() {
        while (!stopped) {
            WatchKey key;
            try {
                key = service.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return;
            }
            Path dir = watchedDirs.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW || dir == null) continue;
                Object ctx = event.context();
                if (kind == StandardWatchEventKinds.ENTRY_CREATE && ctx instanceof Path) {
                    // A newly created subfolder needs its own watch registered, or
                    // files added inside it later would go unnoticed.
                    Path created = dir.resolve((Path) ctx);
                    if (Files.isDirectory(created)) registerDir(created);
                }
            }
            boolean valid = key.reset();
            if (!valid) watchedDirs.remove(key);
            scheduleRescan();
        }
    }

    private synchronized void scheduleRescan() {
        if (pendingRescan != null) pendingRescan.cancel();
        pendingRescan = new TimerTask() {
            @Override
            public void run() {
                if (!stopped) onChange.run();
            }
        };
        debounceTimer.schedule(pendingRescan, DEBOUNCE_MS);
    }
}
