package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.ResourceLoader;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.library.LibraryScanner;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Desktop entry point (the LWJGL twin of {@code QPlayerActivity.onCreate}): wires
 * the platform-neutral {@link PlayerController} over the desktop audio / metadata
 * / color backends, builds the GLFW window + tray, loads {@code Main.qml} from the
 * shared QML tree on the classpath, then runs the main event loop until quit.
 *
 * <p>The render thread (GPU + Skija) is owned by {@link DesktopWindow} and can be
 * destroyed/respawned on minimize-to-tray; the controller, audio and settings live
 * here and survive, so playback continues while hidden.
 *
 * <p>On macOS, launch with {@code -XstartOnFirstThread}.
 */
public final class Main {

    /** True when running from a jpackage-produced bundle (the launcher sets this
     *  property), false on a plain `mvn exec:exec` dev run. */
    private static final boolean PACKAGED = System.getProperty("jpackage.app-path") != null;

    public static void main(String[] args) {
        // The jpackage launcher hands the command line straight to main() instead of
        // to the JVM (and drops -J flags), so pull the -Dkey=value ones back out
        // ourselves. Keeps the packaged app's launch knobs identical to a dev run:
        // -Dqplayer.gfx=vulkan, -Dqplayer.width/height=…, -Dqplayer.tray=false.
        for (String a : args) {
            if (!a.startsWith("-D")) continue;
            int eq = a.indexOf('=');
            if (eq > 2) System.setProperty(a.substring(2, eq), a.substring(eq + 1));
        }

        // Windows packaged launcher (GUI subsystem): no console on double-click, but
        // attach to the launching terminal's console so logs still stream there.
        // Before anything writes to stdout (log4j console appender resolves it).
        if (PACKAGED && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            WinConsole.attachParentConsole();
        }

        // Single instance: if QPlayer is already running, raise its window and exit.
        // Checked before log4j inits so this short-lived second process never opens
        // the shared rolling log file. The activation target is wired once the window
        // exists (below).
        java.util.concurrent.atomic.AtomicReference<Runnable> onActivate =
                new java.util.concurrent.atomic.AtomicReference<>(() -> {});
        if (!SingleInstance.acquire(() -> onActivate.get().run())) {
            return;
        }

        // Put the rolling log under the writable app data dir (~/.qplayer/logs) —
        // when installed to Program Files the working dir isn't writable, so a
        // CWD-relative logs/ would silently fail. Set before log4j2 first inits
        // (in Log4j2Sink below); log4j2.xml reads ${sys:qplayer.logs}.
        if (System.getProperty("qplayer.logs") == null) {
            System.setProperty("qplayer.logs",
                    new File(dev.t1m3.qplayer.store.AppDirs.base(), "logs").getAbsolutePath());
        }

        // Route the shared player-core logger to log4j2 (colored console + rolling
        // file, config in log4j2.xml). Early in main so every later line lands in
        // the configured format.
        Logger.setSink(new Log4j2Sink());

        ResourceLoader resources = new ClasspathResourceLoader();

        // Platform backends (the desktop impls already exist).
        AudioBackend audio = new dev.t1m3.qplayer.desktop.DesktopAudioBackend();
        MetadataReader reader = new dev.t1m3.qplayer.desktop.DesktopMetadataReader();

        PlayerController controller = new PlayerController(audio, reader);
        controller.setColorExtractor(new DesktopColorExtractor());
        controller.setCurrentVersion(appVersion());

        // A downloaded update installer (see downloadAndInstallUpdate below) has
        // served its purpose once the app restarts -- it's never deleted right
        // after launching it (risky to delete a file an installer might still be
        // reading from), and <cacheBase>/updates is a sibling of DiskCache's own
        // cache/ tree, not inside it, so it's otherwise invisible to the
        // cache-size display and "清除缓存" button forever. Sweep it at every
        // startup instead.
        deleteRecursive(new File(AppDirs.cacheBase(), "updates"));

        DesktopSettings settings = new DesktopSettings();
        settings.setMonetListener(controller::setMonetEnabled);
        settings.setUnblockListener(controller::setUnblockEnabled);
        settings.setMirrorListener(controller::setUpdateMirror);
        settings.setFadeListener(controller::setFadeEnabled);
        settings.setHighQualityListener(controller::setHighQualityEnabled);
        settings.setCacheSizeListener(controller::setCacheMaxSizeMB);
        settings.setCustomApiListener(controller::setCustomApiConfig);
        settings.load();

        // Fonts for the host-drawn lyric renderer (the QML scene fonts are set on the
        // view in DesktopWindow.ensureView).
        Fonts.init(
                resources.load("fonts/PingFangSC-Thin.otf"),
                resources.load("fonts/PingFangSC-Light.otf"),
                resources.load("fonts/PingFangSC-Regular.otf"),
                resources.load("fonts/PingFangSC-Medium.otf"));
        Fonts.initIcon(resources.load("fonts/MaterialSymbolsRounded.ttf"));

        byte[] qmlBytes = resources.load("Main.qml");
        if (qmlBytes == null) throw new IllegalStateException("Main.qml not found on classpath");
        String qml = new String(qmlBytes, StandardCharsets.UTF_8);

        QmlEngine engine = new QmlEngine();
        DesktopWindow window = new DesktopWindow(engine, qml, resources, controller, settings);

        // Playback control runs on the main event loop (alive even while the render
        // thread is dead); back/exit folds the window to the tray.
        controller.setMainExecutor(window::postMainTask);
        controller.setExitListener(window::onExitRequested);
        // Open external links (the About page) in the system browser. The Android
        // host uses an ACTION_VIEW intent; on the desktop hand the URL to the OS
        // (no java.awt.Desktop, which needs a working desktop integration and can
        // block on some Linux setups).
        controller.setUrlOpener(Main::openUrl);
        // Pick this OS's own release asset (there's no .apk on a desktop release —
        // PlayerController's default matcher, unchanged for Android, would never
        // match anything here). "setup.exe" (not the plain .zip) so the in-app
        // downloader hands off to the real installer.
        controller.setAssetMatcher(name -> {
            String n = name.toLowerCase();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) return n.endsWith("setup.exe");
            if (os.contains("mac")) return n.endsWith(".dmg");
            return n.endsWith(".appimage");
        });
        controller.setInstaller(urls -> downloadAndInstallUpdate(controller, urls));

        TrayController tray = new TrayController(controller, window, resources.load("app-icon.png"));

        window.init();
        // Start rendering immediately — the render thread is the core; the tray is
        // best-effort and may block on GTK init in some environments, so it must
        // never gate the window coming up.
        window.spawnRenderThread();

        // A second launch now surfaces this window instead of starting a new process.
        onActivate.set(() -> window.postMainTask(window::restoreFromTray));

        // Wire the music-folder change listener so Settings page edits trigger a rescan.
        // Must be wired after window.init() so postRenderTask() is available.
        Object rawFolder = settings.musicFolder.peek();
        String initialFolder = rawFolder instanceof String ? (String) rawFolder : "";
        // Watches the folder tree so adding/removing files is picked up on its own —
        // without this, a rescan only ever ran when the user re-touched the Settings
        // folder field or restarted the app. Each rescan the watcher triggers reuses
        // LibraryScanner's per-file cache, so it stays cheap even on a large library.
        LibraryWatcher watcher = new LibraryWatcher(
                () -> startLibraryScan(controller, reader, window, settings.musicFolder.peek() instanceof String
                        ? (String) settings.musicFolder.peek() : ""));
        settings.setMusicFolderListener(folder -> {
            startLibraryScan(controller, reader, window, folder);
            watcher.start(folder);
        });

        // Cache root (local-library covers/lyrics + netease audio/image/lyric cache).
        // controller.diskCache was already constructed against the AppDirs default
        // above, so a persisted custom folder must be re-applied here before anything
        // reads/writes through it; a later edit re-points it and rescans so the
        // change is visible without a restart.
        Object rawCacheFolder = settings.cacheFolder.peek();
        String initialCacheFolder = rawCacheFolder instanceof String ? (String) rawCacheFolder : "";
        if (!initialCacheFolder.isEmpty()) {
            AppDirs.setCacheBase(initialCacheFolder);
            controller.diskCache.setBaseDir(initialCacheFolder);
        }
        settings.setCacheFolderListener(folder -> {
            AppDirs.setCacheBase(folder);
            controller.diskCache.setBaseDir(folder);
            Object currentFolder = settings.musicFolder.peek();
            startLibraryScan(controller, reader, window, currentFolder instanceof String ? (String) currentFolder : "");
        });

        // Initial content + a background scan of the local music folder.
        controller.loadHome();
        startLibraryScan(controller, reader, window, initialFolder);
        watcher.start(initialFolder);

        // Don't auto-check for updates outside a packaged build — a plain
        // `mvn exec:exec` dev run would otherwise nag on every launch, mirroring
        // QPlayerActivity's debug-build skip on Android.
        if (PACKAGED) {
            controller.checkForUpdate();
        }

        // Tray init on a daemon thread so a GTK/AppIndicator hang can't freeze the app.
        // (-Dqplayer.tray=false disables it, e.g. for headless rendering checks.)
        if (!"false".equals(System.getProperty("qplayer.tray", "true"))) {
            Thread trayThread = getTrayThread(tray, window, controller);
            trayThread.start();
        }

        window.runEventLoop(); // blocks on the main thread until quit

        watcher.stop();
        tray.shutdown();
        try {
            controller.shutdown();
        } catch (Throwable ignored) {
        }
        window.shutdown();
        Logger.info("QPlayer desktop exited");
        killSelf();
    }

    /** Open a URL in the system default browser via the OS handler (no AWT). */
    private static void openUrl(String url) {
        if (url == null || url.isBlank()) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else if (os.contains("mac")) {
            cmd = new String[]{"open", url};
        } else {
            cmd = new String[]{"xdg-open", url};
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            Logger.warn("open url failed ({}): {}", url, e.toString());
        }
    }

    /** {@link PlayerController.Installer}: download the matched release asset into
     *  the cache folder (not a temp dir — same place audio/cover caching already
     *  lives, so it's covered by the existing cache-size/clear-cache settings) and
     *  hand off to the OS to actually install it, mirroring how the Android side
     *  hands a downloaded APK to the system package installer. */
    private static void downloadAndInstallUpdate(PlayerController controller, String[] urls) {
        new Thread(() -> {
            String name = urls.length > 0 ? fileNameOf(urls[0]) : "qplayer-update";
            File dir = new File(AppDirs.cacheBase(), "updates");
            dir.mkdirs();
            File out = new File(dir, name);
            for (String url : urls) {
                if (downloadOne(url, out, controller)) {
                    out.setExecutable(true, false);
                    controller.setUpdateProgress(100);
                    launchInstaller(out);
                    return;
                }
                Logger.warn("update source failed, trying next: {}", url);
            }
            controller.setUpdateProgress(-2);
        }, "qplayer-update-dl").start();
    }

    private static String fileNameOf(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /** Download a single url into {@code out}, reporting progress; false on any
     *  failure (so the caller can try the next mirror), same contract as the
     *  Android downloader this mirrors. */
    private static boolean downloadOne(String url, File out, PlayerController controller) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "qplayer-updater");
            int code = conn.getResponseCode();
            if (code >= 400) return false;
            int total = conn.getContentLength();
            try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                long read = 0;
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int pct = (int) (read * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            controller.setUpdateProgress(pct);
                        }
                    }
                }
            }
            return out.length() > 0;
        } catch (Throwable e) {
            Logger.warn("update download failed {}: {}", url, e.toString());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Hand the downloaded installer off to the OS — same "get out of the way,
     *  let the platform take it from here" spirit as Android's ACTION_VIEW to the
     *  system package installer. Windows: run the Inno Setup exe directly (it
     *  handles the "close the running app" prompt itself). macOS: {@code open} the
     *  dmg (mounts it, Finder shows the drag-to-Applications window). Linux:
     *  AppImage isn't a true installer, so just reveal the containing folder for
     *  the user to swap it in themselves. */
    private static void launchInstaller(File out) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder(out.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", out.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", out.getParentFile().getAbsolutePath()).start();
            }
        } catch (Exception e) {
            Logger.warn("launch installer failed: {}", e.toString());
        }
    }

    /** Running app version from the Maven-filtered version.properties on the
     *  classpath, for the update check. Empty if unavailable. */
    private static String appVersion() {
        try (InputStream is = Main.class.getResourceAsStream("/version.properties")) {
            if (is == null) return "";
            java.util.Properties p = new java.util.Properties();
            p.load(is);
            return p.getProperty("version", "").trim();
        } catch (Exception e) {
            Logger.warn("version.properties read failed: {}", e.toString());
            return "";
        }
    }

    @NotNull
    private static Thread getTrayThread(TrayController tray, DesktopWindow window, PlayerController controller) {
        Thread trayThread = new Thread(() -> {
            boolean ok = false;
            try {
                ok = tray.install();
            } catch (Throwable t) {
                // Never let a tray failure (e.g. an AWT/JNI Error) kill the
                // thread silently and leave trayAvailable unset.
                Logger.warn("tray install threw: {}", t);
            }
            window.setTrayAvailable(ok);
            if (ok) controller.setPlaybackListener(tray);
        }, "qplayer-tray-init");
        trayThread.setDaemon(true);
        return trayThread;
    }

    private static void startLibraryScan(PlayerController controller, MetadataReader reader,
                                         DesktopWindow window, String folder) {
        if (folder == null || folder.isEmpty()) return;
        File music = new File(folder);
        if (!music.isDirectory()) return;
        Thread t = new Thread(() -> {
            try {
                List<Track> tracks = new LibraryScanner(reader).scan(music.getAbsolutePath());
                // Property writes happen on the render thread (mirrors Android's
                // runOnUiThread(controller.scanTracks)).
                window.postRenderTask(() -> controller.scanTracks(tracks));
            } catch (Throwable e) {
                Logger.warn("library scan failed: {}", e);
            }
        }, "qplayer-scan");
        t.setDaemon(true);
        t.start();
    }

    // The desktop GL drivers (notably NVIDIA's) can SIGSEGV a worker thread the
    // instant the process begins to exit, and the AWT EDT (clipboard / tray) is
    // non-daemon and would keep the JVM alive past main(). SIGKILL terminates the
    // whole process atomically before either can bite. Mirrors the qml4j demo host.
    private static void killSelf() {
        try {
            // Java 8: derive the pid from the "pid@host" runtime name.
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            String pid = name.contains("@") ? name.substring(0, name.indexOf('@')) : null;
            if (pid != null) {
                new ProcessBuilder("kill", "-9", pid).start();
                Thread.sleep(10_000);
            }
        } catch (Exception ignored) {
        }
        Runtime.getRuntime().halt(0);
    }
}
