package dev.t1m3.qplayer.desktop.window;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;
import dev.t1m3.qplayer.util.Logger;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns the GLFW window and the render-thread lifecycle on the process main
 * thread. GLFW window/event calls stay here (they must be main-thread; macOS
 * additionally needs {@code -XstartOnFirstThread}); the GPU stack + Skija live on
 * a disposable {@link RenderThread}. Minimize-to-tray stops that thread (which
 * destroys the GPU resources on its own context-owning thread) and hides the
 * window; the persistent {@link QmlView} + {@link PlayerController} stay alive so
 * the audio keeps playing and the UI keeps its state, and restore respawns the
 * render thread against a fresh context.
 */
public final class DesktopWindow {

    private static final int INITIAL_W = Integer.getInteger("qplayer.width", 1100);
    private static final int INITIAL_H = Integer.getInteger("qplayer.height", 720);
    // Logical px. Matches IconButton's own implicitWidth/Height (40) so the custom
    // caption buttons need no size override, while staying slimmer than the 64px
    // TopAppBar so it doesn't read as a second toolbar. Windows-only (custom title
    // bar); see applyWindowsDwmChrome/WinFrameless/WindowChrome.
    private static final double TITLE_BAR_HEIGHT = 40.0;

    private final QmlEngine engine;
    private final String qmlSource;
    private final ResourceLoader resources;
    private final PlayerController controller;
    private final SettingsCore settings;
    private final LyricCompositor compositor = new LyricCompositor();
    private volatile GraphicsBackend.Kind kind;

    // Input events (main thread) marshalled onto the render thread; playback/tray
    // tasks (controller main executor + tray menu) run on the main event loop.
    private final ConcurrentLinkedQueue<Runnable> renderTasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();

    private long window;
    private volatile float uiScale = 1f;
    // Framebuffer size cached from the main-thread callback so the render thread
    // never has to call GLFW.
    private volatile int fbW = INITIAL_W;
    private volatile int fbH = INITIAL_H;
    private volatile int refreshHz = 60;
    private volatile int[] pendingResize;

    // Persistent across render-thread respawns (built once, on the render thread).
    private volatile QmlView view;
    private boolean lastSpawnWasRespawn;

    private InputBridge input;
    // Captured in init(); GLFW window/event/clipboard calls must run here.
    private volatile Thread mainThread;
    private volatile RenderThread renderThread;
    private volatile boolean quitRequested;
    private volatile boolean hiddenToTray;
    private boolean graphicsFallbackAttempted;
    // Whether a system tray actually installed. Without one, hiding the window would
    // make the app vanish with no way back, so the close button quits instead and
    // recovery is left to the window manager's minimise (the taskbar icon is set).
    private volatile boolean trayAvailable;
    private Runnable firstFrameListener;
    // Windows-only custom title bar. Both null on other platforms; a fresh
    // WinFrameless is created per createWindow() call (see its own javadoc for why).
    private WindowChrome windowChrome;
    private WinFrameless frameless;

    public DesktopWindow(QmlEngine engine, String qmlSource, ResourceLoader resources,
                  PlayerController controller, SettingsCore settings) {
        this.engine = engine;
        this.qmlSource = qmlSource;
        this.resources = resources;
        this.controller = controller;
        this.settings = settings;
        this.kind = GraphicsBackend.Kind.resolve(settings);
    }

    // --- accessors used by the render thread (all read cached/persistent state) ---

    public long window() {
        return window;
    }

    Thread mainThread() {
        return mainThread;
    }

    QmlView view() {
        return view;
    }

    GraphicsBackend.Kind kind() {
        return kind;
    }

    float uiScale() {
        return uiScale;
    }

    int[] framebufferSize() {
        return new int[]{fbW, fbH};
    }

    int refreshHz() {
        return refreshHz;
    }

    PlayerController controller() {
        return controller;
    }

    SettingsCore settings() {
        return settings;
    }

    LyricCompositor compositor() {
        return compositor;
    }

    public void setFirstFrameListener(Runnable r) {
        this.firstFrameListener = r;
    }

    /**
     * Post a task to run on the render thread (input events).
     */
    public void postRenderTask(Runnable r) {
        renderTasks.add(r);
    }

    /**
     * Post a task to run on the main event loop (playback control, tray).
     */
    public void postMainTask(Runnable r) {
        mainTasks.add(r);
    }

    void drainRenderTasks() {
        Runnable r;
        while ((r = renderTasks.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                Logger.warn("render task failed: {}", t);
            }
        }
    }

    /**
     * Per-frame input animation (smooth wheel scrolling); render thread.
     */
    void tickInput() {
        if (input != null) input.tickScroll();
    }

    int[] consumePendingResize() {
        int[] r = pendingResize;
        if (r != null) pendingResize = null;
        return r;
    }

    /**
     * Build the persistent QML view on first call (render thread), else return it.
     * Records whether this was a respawn so the caller can invalidate GPU caches.
     */
    QmlView ensureView() {
        if (view != null) {
            lastSpawnWasRespawn = true;
            return view;
        }
        lastSpawnWasRespawn = false;
        QmlView v = QmlView.withStockTypes(engine).resources(resources);
        v.setClipboard(new GlfwClipboard(this));
        if (controller != null) v.context("player", controller);
        if (settings != null) v.context("settings", settings);
        // hostWindow must always be registered, even on mac/Linux where there's no
        // custom title bar -- qml4j's compiler rejects an undeclared top-level
        // identifier at compile time, so shared-qml can't just have it be absent
        // (see WindowChromeStub's javadoc). The real WindowChrome only exists on
        // Windows (windowChrome != null); everywhere else gets the no-op stub.
        v.context("hostWindow", windowChrome != null
                ? windowChrome : new dev.t1m3.qplayer.bridge.WindowChromeStub());
        loadFonts(v, resources);
        v.load(qmlSource);
        view = v;
        return v;
    }

    /** Load the fonts QML's own UI text is drawn with. The lyric page resolves its
     *  face through Skija's FontMgr (Fonts.setSelection) and needs nothing here, but
     *  qml4j's uiTypefaces only takes raw font-file BYTES — no Typeface-object
     *  overload — so following the same setting for the UI means locating the actual
     *  file behind {@link Fonts#activeFamilyName()} on disk. That lookup is
     *  per-platform ({@link #findSystemFontFile}) and best-effort: anything that
     *  doesn't resolve falls back to the bundled PingFang OTF, which is also what a
     *  bundled-font selection uses directly. Read once per view spawn, hence the
     *  "restart to apply" note in Settings. */
    public static void loadFonts(QmlView v, ResourceLoader resources) {
        byte[] reg = null, med = null;
        String family = Fonts.activeFamilyName();
        if (family != null) {
            reg = findSystemFontFile(family, false);
            med = findSystemFontFile(family, true);
        }
        if (reg == null) reg = resources.load("fonts/PingFangSC-Regular.otf");
        if (med == null) med = resources.load("fonts/PingFangSC-Medium.otf");
        if (reg != null || med != null) v.uiTypefaces(reg, med);
        byte[] iconFont = resources.load("fonts/MaterialSymbolsRounded.ttf");
        if (iconFont != null) v.iconTypeface(iconFont);
    }

    /** The regular (or bold) font file of an installed family, as bytes; null when
     *  this platform's lookup can't find one. Windows reads the registry index,
     *  Linux asks fontconfig, macOS scans the standard font directories — Skija
     *  itself exposes no path for a Typeface, so there's no shared shortcut. */
    private static byte[] findSystemFontFile(String family, boolean bold) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        try {
            if (os.contains("win")) return readWindowsFontByFamily(family, bold);
            if (os.contains("mac")) return readMacFontByFamily(family, bold);
            return readFontconfigFontByFamily(family, bold);
        } catch (Throwable ignored) {
            return null; // any lookup failure → the bundled font
        }
    }

    /** Linux: fontconfig already indexes every installed family and answers with the
     *  file path directly, so there's nothing to scan. {@code fc-match} is part of
     *  the fontconfig package every desktop distro pulls in; if it's missing or slow
     *  the caller falls back to the bundled font. */
    private static byte[] readFontconfigFontByFamily(String family, boolean bold) {
        try {
            Process p = new ProcessBuilder("fc-match", "-f", "%{file}",
                    family + ":weight=" + (bold ? "bold" : "regular"))
                    .redirectErrorStream(false).start();
            String path;
            try (java.io.InputStream in = p.getInputStream()) {
                path = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (path.isEmpty()) return null;
            java.io.File f = new java.io.File(path);
            return f.isFile() ? java.nio.file.Files.readAllBytes(f.toPath()) : null;
        } catch (Throwable ignored) {
            return null; // no fc-match on PATH, unreadable file, ...
        }
    }

    // macOS font locations, in the order CoreText itself searches them.
    private static final String[] MAC_FONT_DIRS = {
        System.getProperty("user.home", "") + "/Library/Fonts",
        "/Library/Fonts",
        "/System/Library/Fonts",
        "/System/Library/Fonts/Supplemental",
    };

    /** macOS: no registry, and no CLI that reports a family's file path (the closest,
     *  {@code system_profiler SPFontsDataType}, takes seconds), so open each file in
     *  the standard font directories with Skija and keep the one whose family name
     *  and weight match. Runs once per launch and only when a non-bundled font is
     *  selected. */
    private static byte[] readMacFontByFamily(String family, boolean bold) {
        io.github.humbleui.skija.FontMgr mgr = io.github.humbleui.skija.FontMgr.getDefault();
        if (mgr == null) return null;
        int wanted = bold ? 700 : 400;
        java.io.File best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String dir : MAC_FONT_DIRS) {
            java.io.File[] files = new java.io.File(dir).listFiles();
            if (files == null) continue;
            for (java.io.File f : files) {
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".ttf") && !name.endsWith(".otf")
                        && !name.endsWith(".ttc") && !name.endsWith(".otc")) continue;
                try {
                    io.github.humbleui.skija.Typeface t = mgr.makeFromFile(f.getAbsolutePath());
                    if (t == null || !family.equalsIgnoreCase(t.getFamilyName())) continue;
                    int distance = Math.abs(t.getFontStyle().getWeight() - wanted);
                    if (distance < bestDistance) { bestDistance = distance; best = f; }
                } catch (Throwable ignored) {
                    // Unparseable/permission-denied file — just skip it.
                }
            }
        }
        try {
            return best != null ? java.nio.file.Files.readAllBytes(best.toPath()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Registry key Windows itself keeps up to date with every installed font:
    // display name ("Arial (TrueType)", "Arial Bold (TrueType)", ...) -> filename
    // (relative to %WINDIR%\Fonts, or an absolute path for a per-user install).
    private static final String FONTS_REGISTRY_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts";

    /** Resolve a plain family name (as listed by Fonts.listFamilies(), e.g. "Arial")
     *  to that family's regular/bold font file bytes via the registry's display-name
     *  index — QmlView.uiTypefaces needs raw file bytes, not a Typeface object (see
     *  the class-level note above), so unlike the Skija lyric-page path there's no
     *  way around reading an actual file. Windows-only; returns null anywhere the
     *  lookup can't complete (not Windows, family not installed, unreadable file). */
    private static byte[] readWindowsFontByFamily(String family, boolean bold) {
        String winDir = System.getenv("WINDIR");
        if (winDir == null || winDir.isEmpty()) return null;
        try {
            java.util.Map<String, Object> values = com.sun.jna.platform.win32.Advapi32Util.registryGetValues(
                    com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, FONTS_REGISTRY_KEY);
            String lowerFamily = family.toLowerCase(java.util.Locale.ROOT);
            String bestFile = null;
            int bestScore = Integer.MIN_VALUE;
            for (java.util.Map.Entry<String, Object> e : values.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                String displayName = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!displayName.startsWith(lowerFamily)) continue;
                boolean isBold = displayName.contains("bold");
                boolean isItalic = displayName.contains("italic") || displayName.contains("oblique");
                int score = 0;
                if (isBold == bold) score += 2; // matches the weight we're after
                if (!isItalic) score += 1;       // prefer upright over italic either way
                if (displayName.equals(lowerFamily + (bold ? " bold (truetype)" : " (truetype)"))) score += 4;
                if (score > bestScore) { bestScore = score; bestFile = (String) e.getValue(); }
            }
            if (bestFile == null) return null;
            java.io.File f = new java.io.File(bestFile);
            if (!f.isAbsolute()) f = new java.io.File(winDir + "\\Fonts\\" + bestFile);
            return f.isFile() ? java.nio.file.Files.readAllBytes(f.toPath()) : null;
        } catch (Throwable ignored) {
            return null; // missing registry key, JNA unavailable, unreadable file, ...
        }
    }

    boolean markViewLive() {
        return lastSpawnWasRespawn;
    }

    void onFirstFramePainted() {
        // The window is created hidden so the first visible frame is real content,
        // not a blank flash during the QML compile. Show it now (on the main thread,
        // where GLFW window ops must run).
        postMainTask(() -> {
            if (!hiddenToTray) {
                GLFW.glfwShowWindow(window);
                GLFW.glfwFocusWindow(window);
            }
        });
        if (windowChrome != null) postMainTask(this::nudgeResizeOnce);
        Runnable r = firstFrameListener;
        if (r != null) {
            firstFrameListener = null;
            postMainTask(r);
        }
    }

    void onRenderError(Throwable t) {
        Logger.error("render thread crashed: {}", t);
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        Logger.error(sw.toString());
        if (kind == GraphicsBackend.Kind.VULKAN && !graphicsFallbackAttempted) {
            graphicsFallbackAttempted = true;
            postMainTask(this::fallbackToOpenGL);
        }
    }

    // --- main-thread lifecycle -------------------------------------------------

    public void init() {
        mainThread = Thread.currentThread();
        GLFWErrorCallback.createPrint(System.err).set();
        preferStablePlatform();
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");

        if (kind == GraphicsBackend.Kind.VULKAN
                && !org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported()) {
            Logger.warn("Vulkan is unavailable; falling back to OpenGL");
            graphicsFallbackAttempted = true;
            kind = GraphicsBackend.Kind.GL;
            settings.put("graphicsBackend", 0);
            settings.graphicsFallbackNotice.set(Boolean.TRUE);
        }
        createWindow();
    }

    private void createWindow() {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        if (kind == GraphicsBackend.Kind.VULKAN) {
            // Vulkan manages its own surface; GLFW must not create a GL context.
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        } else {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        }

        window = GLFW.glfwCreateWindow(INITIAL_W, INITIAL_H, "QPlayer", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            GLFW.glfwTerminate();
            throw new IllegalStateException("glfwCreateWindow failed");
        }
        // IMPORTANT: do NOT make the GL context current here — the render thread
        // owns it. (For Vulkan there is no GL context at all.)

        setWindowIcon();
        applyWindowsDwmChrome();
        if (isWindows()) {
            // windowChrome is reused across a Vulkan-fallback recreate (it only ever
            // delegates through this DesktopWindow's own accessors, never captures a
            // specific hwnd, so it stays valid) -- but frameless subclasses one
            // specific hwnd's WNDPROC, so it MUST be a fresh instance every time
            // createWindow() runs, or a stale one could receive a callback for an
            // already-destroyed window.
            if (windowChrome == null) windowChrome = new WindowChrome(this);
            frameless = new WinFrameless();
            frameless.install(this, TITLE_BAR_HEIGHT);
            settings.setInsets(TITLE_BAR_HEIGHT, settings.bottomInset.peek());
        }
        cacheFramebufferAndScale();
        cacheRefreshRate();
        installCallbacks();
        input = new InputBridge(this);
        input.install(window);
        Logger.info("desktop window created ({}x{}), graphics backend = {}", fbW, fbH, kind);
    }

    /** dwmapi.dll's DwmSetWindowAttribute, used only for the two chrome attributes
     *  below. Fully-qualified JNA types (matches this file's other rare/localized
     *  JNA usage, {@link #readWindowsFontByFamily}) rather than adding top-level
     *  imports for a single small interface. */
    private interface Dwmapi extends com.sun.jna.win32.StdCallLibrary {
        Dwmapi I = com.sun.jna.Native.load("dwmapi", Dwmapi.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
        int DwmSetWindowAttribute(com.sun.jna.Pointer hwnd, int dwAttribute,
                com.sun.jna.Pointer pvAttribute, int cbAttribute);
    }

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private static final int DWMWCP_ROUND = 2;

    /** Windows-only chrome polish. GLFW creates a plain top-level window with the
     *  OS's default (light) frame; a Windows 11 desktop won't necessarily round its
     *  corners on its own (the DWM heuristic that does this automatically doesn't
     *  reliably trigger for a bare GL/Vulkan-surfaced window), and the frame stays
     *  light even though the UI right below it is dark — both need an explicit
     *  opt-in via DwmSetWindowAttribute. Read the theme once at window creation
     *  (same "restart to apply" convention as the font selection above) rather than
     *  wiring a live listener for a mid-session theme switch. Best-effort: any
     *  failure (pre-Windows-11 corner attribute, no dwmapi, ...) just leaves the OS
     *  default frame, so this never blocks startup. */
    /** Windows-only cold-start workaround: the custom title bar's reserved top
     *  strip (settings.topInset, already 40 before the QML view is ever built --
     *  see createWindow()) renders as a few stray pixels on the very first frames
     *  and stays that way indefinitely; reproduced down to a plain colored
     *  Rectangle with no TitleBar-specific logic, so this is a qml4j cold-start
     *  compositing quirk (same general class as the Tabs indicator's documented
     *  cold-start settle issue), not a bug in this app's own bindings. A real
     *  WM_SIZE round trip reliably un-sticks it (confirmed live), so nudge the
     *  window size by 1px and back once, right after the first frame is shown --
     *  imperceptible, and only runs when the custom title bar is actually active. */
    private void nudgeResizeOnce() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(window, w, h);
            int ww = w.get(0), wh = h.get(0);
            GLFW.glfwSetWindowSize(window, ww, wh + 1);
            GLFW.glfwSetWindowSize(window, ww, wh);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private void applyWindowsDwmChrome() {
        if (!isWindows()) return;
        try {
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
            com.sun.jna.Pointer h = com.sun.jna.Pointer.createConstant(hwnd);
            com.sun.jna.Memory corner = new com.sun.jna.Memory(4);
            corner.setInt(0, DWMWCP_ROUND);
            Dwmapi.I.DwmSetWindowAttribute(h, DWMWA_WINDOW_CORNER_PREFERENCE, corner, 4);
            com.sun.jna.Memory dark = new com.sun.jna.Memory(4);
            dark.setInt(0, settings != null && settings.resolvedDarkValue() ? 1 : 0);
            Dwmapi.I.DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE, dark, 4);
        } catch (Throwable t) {
            Logger.warn("Windows DWM chrome attributes failed: {}", t.getMessage());
        }
    }

    /**
     * A Vulkan device/surface can fail after GLFW's basic availability probe.
     * Recreate the window with an OpenGL context and keep the application alive.
     * Runs on the GLFW-owning main thread after the failed render thread exits.
     */
    private void fallbackToOpenGL() {
        if (kind != GraphicsBackend.Kind.VULKAN || quitRequested) return;
        Logger.warn("Vulkan initialization failed; rebuilding the window with OpenGL");
        stopRenderThread();
        if (window != MemoryUtil.NULL) {
            org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
            GLFW.glfwDestroyWindow(window);
            window = MemoryUtil.NULL;
        }
        kind = GraphicsBackend.Kind.GL;
        settings.put("graphicsBackend", 0);
        settings.graphicsFallbackNotice.set(Boolean.TRUE);
        pendingResize = null;
        try {
            createWindow();
            spawnRenderThread();
        } catch (Throwable fallbackError) {
            Logger.error("OpenGL fallback failed: {}", fallbackError);
            requestQuit();
        }
    }

    private void cacheRefreshRate() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor != MemoryUtil.NULL ? GLFW.glfwGetVideoMode(monitor) : null;
        int hz = mode != null ? mode.refreshRate() : 0;
        // Broken/virtual displays occasionally report zero or nonsense. Keep the
        // fallback conservative while allowing modern high-refresh panels.
        refreshHz = hz >= 30 && hz <= 360 ? hz : 60;
        Logger.info("display refresh rate = {} Hz", refreshHz);
    }

    // Rendering happens on a dedicated thread while the main thread pumps events.
    // On a Wayland session NVIDIA's EGL driver crashes under that split; X11/GLX
    // (via XWayland) is stable, so prefer X11 when a DISPLAY is available. Override
    // with -Dqplayer.glfw.platform=wayland|x11|any.
    private void preferStablePlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) return;
        String pref = System.getProperty("qplayer.glfw.platform", "").toLowerCase();
        int platform;
        if ("wayland".equals(pref)) {
            platform = GLFW.GLFW_PLATFORM_WAYLAND;
        } else if ("any".equals(pref)) {
            return;
        } else if ("x11".equals(pref) || System.getenv("DISPLAY") != null) {
            platform = GLFW.GLFW_PLATFORM_X11;
        } else {
            return;
        }
        if (GLFW.glfwPlatformSupported(platform)) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, platform);
            Logger.info("forcing GLFW platform = {}",
                    platform == GLFW.GLFW_PLATFORM_X11 ? "x11" : "wayland");
        }
    }

    private void cacheFramebufferAndScale() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, w, h);
            fbW = Math.max(1, w.get(0));
            fbH = Math.max(1, h.get(0));
            java.nio.FloatBuffer sx = stack.mallocFloat(1), sy = stack.mallocFloat(1);
            GLFW.glfwGetWindowContentScale(window, sx, sy);
            float s = sx.get(0);
            uiScale = s > 0 ? s : 1f;
        }
    }

    @SuppressWarnings("resource")
    private void installCallbacks() {
        GLFW.glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            if (w <= 0 || h <= 0) return;
            fbW = w;
            fbH = h;
            pendingResize = new int[]{w, h};
        });
        GLFW.glfwSetWindowContentScaleCallback(window, (win, sx, sy) -> {
            if (sx > 0) uiScale = sx;
        });
        // With a tray: close hides to it (real quit = tray "Quit"). Without a tray:
        // close just quits, so the app can't vanish to nowhere.
        GLFW.glfwSetWindowCloseCallback(window, win -> {
            // Veto the actual close; onExitRequested decides hide-to-tray vs quit.
            GLFW.glfwSetWindowShouldClose(win, false);
            onExitRequested();
        });
        // Both fire on the main thread; windowChrome's Properties drive QML, so their
        // mutation is marshalled to the render thread like PlayerController's own
        // Property writes. Maximized state can change via the new custom button, but
        // also natively (double-click the empty title bar, drag-to-edge Snap) -- this
        // is the only way TitleBar.qml's maximize/restore glyph stays correct either way.
        GLFW.glfwSetWindowMaximizeCallback(window, (win, max) -> {
            if (windowChrome != null) postRenderTask(() -> windowChrome.maximized.set(max));
        });
        GLFW.glfwSetWindowFocusCallback(window, (win, foc) -> {
            if (windowChrome != null) postRenderTask(() -> windowChrome.focused.set(foc));
        });
    }

    /**
     * Told by the host once the tray install has finished (on its own thread).
     */
    public void setTrayAvailable(boolean available) {
        this.trayAvailable = available;
    }

    private void setWindowIcon() {
        byte[] png = resources.load("app-icon.png");
        if (png == null) return;
        List<ByteBuffer> pixelBuffers = new ArrayList<>();
        try {
            BufferedImage source = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
            if (source == null) return;
            int[] sizes = {16, 20, 24, 32, 40, 48, 64, 256};
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GLFWImage.Buffer icons = GLFWImage.malloc(sizes.length, stack);
                for (int i = 0; i < sizes.length; i++) {
                    BufferedImage image = smoothScale(source, sizes[i], sizes[i]);
                    ByteBuffer pixels = rgba(image);
                    pixelBuffers.add(pixels);
                    icons.position(i)
                            .width(image.getWidth())
                            .height(image.getHeight())
                            .pixels(pixels);
                }
                icons.position(0);
                GLFW.glfwSetWindowIcon(window, icons);
            }
        } catch (Throwable t) {
            Logger.warn("window icon load failed: {}", t);
        } finally {
            for (ByteBuffer pixels : pixelBuffers) MemoryUtil.memFree(pixels);
        }
    }

    private static BufferedImage smoothScale(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = scale(current,
                    Math.max(width, current.getWidth() / 2),
                    Math.max(height, current.getHeight() / 2));
        }
        return current.getWidth() == width && current.getHeight() == height
                ? current : scale(current, width, height);
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static ByteBuffer rgba(BufferedImage image) {
        ByteBuffer pixels = MemoryUtil.memAlloc(image.getWidth() * image.getHeight() * 4);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                pixels.put((byte) ((p >> 16) & 0xFF));
                pixels.put((byte) ((p >> 8) & 0xFF));
                pixels.put((byte) (p & 0xFF));
                pixels.put((byte) ((p >> 24) & 0xFF));
            }
        }
        return pixels.flip();
    }

    public void spawnRenderThread() {
        RenderThread rt = renderThread;
        if (rt != null && rt.isAlive()) return;
        rt = new RenderThread(this);
        renderThread = rt;
        rt.start();
    }

    void stopRenderThread() {
        RenderThread rt = renderThread;
        if (rt == null) return;
        rt.shutdown();
        try {
            rt.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        renderThread = null;
    }

    /**
     * Close-button / back policy: hide to the tray if there is one, else quit (so
     * the app never vanishes with no way back).
     */
    public void onExitRequested() {
        if (trayAvailable) minimizeToTray();
        else requestQuit();
    }

    /**
     * Tear down the render thread + GPU stack and hide the window (main thread).
     */
    void minimizeToTray() {
        if (hiddenToTray) return;
        hiddenToTray = true;
        stopRenderThread();
        GLFW.glfwHideWindow(window);
        Logger.info("minimized to tray (render thread + GPU destroyed)");
    }

    /**
     * Show the window again and respawn the render thread (main thread).
     */
    public void restoreFromTray() {
        boolean wasHidden = hiddenToTray;
        hiddenToTray = false;
        // Un-iconify only when actually minimized — glfwRestoreWindow would also
        // un-maximize a maximized window, which we don't want.
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
            GLFW.glfwRestoreWindow(window);
        }
        GLFW.glfwShowWindow(window);
        GLFW.glfwFocusWindow(window);
        // On X11/Wayland the WM often refuses a forced raise (glfwFocusWindow is a
        // no-op under Wayland), so also flag the taskbar entry for attention. GLFW
        // skips this when the window is already focused, so it's a no-op on Windows
        // where AllowSetForegroundWindow already raised us.
        GLFW.glfwRequestWindowAttention(window);
        if (wasHidden) {
            spawnRenderThread();
            Logger.info("restored from tray (render thread respawned)");
        }
    }

    boolean isHiddenToTray() {
        return hiddenToTray;
    }

    public void requestQuit() {
        quitRequested = true;
        GLFW.glfwPostEmptyEvent();
    }

    /**
     * Main loop: pump GLFW events + the main-task queue until quit. Keeps running
     * (and draining playback/tray tasks) even while the render thread is dead.
     */
    public void runEventLoop() {
        while (!quitRequested) {
            // Block briefly so a hidden-to-tray window doesn't spin the CPU, but stay
            // responsive to tray actions posted to the main-task queue.
            GLFW.glfwWaitEventsTimeout(0.05);
            Runnable r;
            while ((r = mainTasks.poll()) != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Logger.warn("main task failed: {}", t);
                }
            }
        }
    }

    public void shutdown() {
        stopRenderThread();
        if (view != null) {
            try {
                view.dispose();
            } catch (Throwable ignored) {
            }
        }
        org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        @SuppressWarnings("resource") GLFWErrorCallback cb = GLFW.glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }
}
