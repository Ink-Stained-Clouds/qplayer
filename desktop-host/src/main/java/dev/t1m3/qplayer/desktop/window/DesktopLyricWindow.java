package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.desktop.resources.DiskCompiledSceneCache;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.settings.SettingsStore;
import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.render.ResourceLoader;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Main-thread GLFW shell for desktop lyrics. GPU and QML ownership live in a
 * dedicated {@link DesktopLyricRenderThread}; this class only manages the native
 * window and publishes immutable controller/settings snapshots to that thread.
 */
public final class DesktopLyricWindow {

    private static final String ENABLED_KEY = "desktopLyricEnabled";
    private static final String X_KEY = "desktopLyricX";
    private static final String Y_KEY = "desktopLyricY";
    static final int WIDTH = 760;
    static final int HEIGHT = 118;

    private final SettingsStore store;
    private final ResourceLoader resources;
    private final DiskCompiledSceneCache qmlCompilationCache;
    private final String qmlSource;
    private final Consumer<Boolean> settingsWriter;
    private final Consumer<Runnable> mainPoster;
    private final AtomicReference<DesktopLyricSnapshot> snapshot =
            new AtomicReference<>(DesktopLyricSnapshot.EMPTY);
    private final AtomicReference<FramebufferSize> framebufferSize =
            new AtomicReference<>(new FramebufferSize(WIDTH, HEIGHT));

    private volatile boolean enabled;
    private volatile boolean snapshotPublished;
    private volatile DesktopLyricRenderThread renderThread;
    private volatile long window = MemoryUtil.NULL;
    private volatile boolean firstFrameReady;
    private GraphicsBackend.Kind kind;
    private boolean positioningSupported = true;
    private List<LyricLine> lastLines;
    private boolean lastLinearPlainLrc;
    private LyricTimeline.Prepared prepared;

    private boolean dragging;
    private double dragCursorX0;
    private double dragCursorY0;

    DesktopLyricWindow(SettingsStore store, ResourceLoader resources,
                       GraphicsBackend.Kind kind, DiskCompiledSceneCache qmlCompilationCache,
                       Consumer<Boolean> settingsWriter,
                       Consumer<Runnable> mainPoster) {
        this.store = store;
        this.resources = resources;
        this.qmlCompilationCache = qmlCompilationCache;
        this.kind = transparentBackend(kind);
        this.settingsWriter = settingsWriter;
        this.mainPoster = mainPoster;
        byte[] bytes = resources.load("DesktopLyric.qml");
        if (bytes == null) throw new IllegalStateException("DesktopLyric.qml not found on classpath");
        this.qmlSource = new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return enabled;
    }

    GraphicsBackend.Kind kind() {
        return kind;
    }

    long window() {
        return window;
    }

    ResourceLoader resources() {
        return resources;
    }

    DiskCompiledSceneCache qmlCompilationCache() {
        return qmlCompilationCache;
    }

    String qmlSource() {
        return qmlSource;
    }

    DesktopLyricSnapshot snapshot() {
        return snapshot.get();
    }

    boolean hasPublishedSnapshot() {
        return snapshotPublished;
    }

    FramebufferSize framebufferSize() {
        return framebufferSize.get();
    }

    /** Main thread: creates the native surface using the selected app backend. */
    void create() {
        positioningSupported = GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND;
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        // Desktop lyrics is an auxiliary overlay, never a normal document window:
        // keep it undecorated so the framebuffer can stay transparent and entirely
        // app-rendered on every supported window system.
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        if (kind == GraphicsBackend.Kind.VULKAN) {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        } else {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
            GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 8);
        }
        window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "QPlayer Lyrics",
                MemoryUtil.NULL, MemoryUtil.NULL);
        GLFW.glfwDefaultWindowHints();
        if (window == MemoryUtil.NULL) {
            Logger.warn("desktop lyric window: glfwCreateWindow failed");
            return;
        }
        boolean transparent = GLFW.glfwGetWindowAttrib(
                window, GLFW.GLFW_TRANSPARENT_FRAMEBUFFER) == GLFW.GLFW_TRUE;
        DesktopWindow.applyWindowsRoundedCorners(window);
        Logger.info("desktop lyric window created (backend {}, transparent framebuffer = {})",
                kind, transparent);
        if (!positioningSupported) {
            Logger.warn("desktop lyrics disabled: native Wayland cannot move or persist "
                    + "an undecorated window; enable XWayland so QPlayer can use X11");
        }
        cacheFramebufferSize();
        GLFW.glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            if (width > 0 && height > 0) {
                framebufferSize.set(new FramebufferSize(width, height));
            }
        });
        int x = store.getInt(X_KEY, -1);
        int y = store.getInt(Y_KEY, -1);
        if (positioningSupported && x >= 0 && y >= 0) GLFW.glfwSetWindowPos(window, x, y);
        else if (positioningSupported) centerBottom();
        if (positioningSupported) installDragHandlers();
        boolean requestedEnabled = store.getBool(ENABLED_KEY, false);
        enabled = positioningSupported && requestedEnabled;
        if (!positioningSupported) {
            store.putBool(ENABLED_KEY, false);
            if (requestedEnabled && settingsWriter != null) settingsWriter.accept(false);
        }
        // Stay hidden until qml4j has compiled and presented a real transparent
        // frame. Showing an uninitialized native backbuffer produces a black box.
        firstFrameReady = false;
        snapshotPublished = false;
    }

    /**
     * Starts the independent desktop-lyric GPU/QML owner once. The scene is
     * prewarmed even while disabled so enabling it later never stalls the main
     * QML renderer for compilation.
     */
    synchronized void startRenderThread() {
        if (window == MemoryUtil.NULL) return;
        DesktopLyricRenderThread current = renderThread;
        if (current != null && current.isAlive()) return;
        DesktopLyricRenderThread thread = new DesktopLyricRenderThread(this);
        renderThread = thread;
        thread.start();
    }

    /** Main startup thread: keep the hidden main window hidden until qml4j's
     * process-global initialization for the second scene has completed. */
    void awaitRenderThreadReady() {
        startRenderThread();
        DesktopLyricRenderThread thread = renderThread;
        if (thread == null) return;
        try {
            if (!thread.awaitInitialized(30_000L)) {
                Logger.warn("desktop lyric renderer prewarm timed out");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Main render thread: copy all non-thread-safe QML/controller state. */
    void publish(PlayerController controller, boolean dark) {
        if (controller == null) return;
        List<LyricLine> lines = controller.lyrics.peek();
        LyricConfig config = LyricConfig.instance;
        boolean linear = Boolean.TRUE.equals(config.linearAnimForPlainLrc.getValue());
        if (lines != lastLines || linear != lastLinearPlainLrc) {
            lastLines = lines;
            lastLinearPlainLrc = linear;
            prepared = LyricTimeline.prepare(lines, linear);
        }
        int fontSize = config.lyricFontSize.getValue();
        int fontWeight = config.fontWeight.getValue().ordinal();
        boolean shadow = Boolean.TRUE.equals(config.dropShadow.getValue());
        snapshot.set(new DesktopLyricSnapshot(prepared,
                controller.title.peek(), controller.artist.peek(),
                controller.lyricClockPosition(), controller.isLyricClockRunning(),
                System.nanoTime(), config.offsetMs.getValue(),
                fontSize, fontWeight, shadow, dark));
        boolean firstSnapshot = !snapshotPublished;
        snapshotPublished = true;
        if (firstSnapshot) {
            DesktopLyricRenderThread thread = renderThread;
            if (thread != null) java.util.concurrent.locks.LockSupport.unpark(thread);
        }
    }

    /** Main thread. */
    public void setEnabled(boolean value) {
        applyEnabled(value);
        if (settingsWriter != null) settingsWriter.accept(enabled);
    }

    /** Main thread: applies a SettingsCore-originated change without echoing it. */
    void applyEnabled(boolean value) {
        if (window == MemoryUtil.NULL) return;
        if (value && !positioningSupported) {
            enabled = false;
            store.putBool(ENABLED_KEY, false);
            Logger.warn("cannot enable desktop lyrics on native Wayland; "
                    + "start QPlayer through XWayland to keep it transparent and draggable");
            if (settingsWriter != null) settingsWriter.accept(false);
            return;
        }
        enabled = value;
        store.putBool(ENABLED_KEY, value);
        if (value) {
            startRenderThread();
            if (firstFrameReady) GLFW.glfwShowWindow(window);
            DesktopLyricRenderThread thread = renderThread;
            if (thread != null) java.util.concurrent.locks.LockSupport.unpark(thread);
        } else {
            GLFW.glfwHideWindow(window);
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Main thread, used when the main Vulkan backend falls back before first frame. */
    void recreate(GraphicsBackend.Kind newKind) {
        shutdownRenderThread();
        disposeWindow();
        kind = transparentBackend(newKind);
        create();
    }

    void onRenderError(Throwable error) {
        Logger.error("desktop lyric render thread crashed: {}", error);
        if (mainPoster != null) mainPoster.accept(() -> setEnabled(false));
    }

    void onFirstFrameRendered() {
        firstFrameReady = true;
        if (mainPoster != null) mainPoster.accept(() -> {
            if (enabled && window != MemoryUtil.NULL) GLFW.glfwShowWindow(window);
        });
    }

    void shutdown() {
        shutdownRenderThread();
        disposeWindow();
    }

    private void shutdownRenderThread() {
        DesktopLyricRenderThread thread = renderThread;
        if (thread == null) return;
        thread.shutdown();
        try {
            thread.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        renderThread = null;
    }

    private void centerBottom() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) return;
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode != null) GLFW.glfwSetWindowPos(window,
                (mode.width() - WIDTH) / 2, mode.height() - HEIGHT - 96);
    }

    private void installDragHandlers() {
        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == GLFW.GLFW_PRESS) {
                dragging = true;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    DoubleBuffer cursorX = stack.mallocDouble(1);
                    DoubleBuffer cursorY = stack.mallocDouble(1);
                    GLFW.glfwGetCursorPos(win, cursorX, cursorY);
                    dragCursorX0 = cursorX.get(0);
                    dragCursorY0 = cursorY.get(0);
                }
            } else if (action == GLFW.GLFW_RELEASE && dragging) {
                dragging = false;
                persistPosition();
            }
        });
        GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (!dragging) return;
            // Cursor coordinates are window-local. Read the CURRENT window origin,
            // not the press-time origin: moving the window changes the local cursor
            // coordinate even when the physical pointer stands still. Adding the
            // delta to the current origin cancels that feedback and stays in GLFW's
            // own screen-coordinate units on HiDPI/Retina displays.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer windowX = stack.mallocInt(1);
                IntBuffer windowY = stack.mallocInt(1);
                GLFW.glfwGetWindowPos(win, windowX, windowY);
                GLFW.glfwSetWindowPos(win,
                        (int) Math.round(windowX.get(0) + x - dragCursorX0),
                        (int) Math.round(windowY.get(0) + y - dragCursorY0));
            }
        });
    }

    private void cacheFramebufferSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, width, height);
            framebufferSize.set(new FramebufferSize(
                    Math.max(1, width.get(0)), Math.max(1, height.get(0))));
        }
    }

    private void persistPosition() {
        if (window == MemoryUtil.NULL) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(window, x, y);
            store.putInt(X_KEY, x.get(0));
            store.putInt(Y_KEY, y.get(0));
        }
    }

    private void disposeWindow() {
        long handle = window;
        if (handle == MemoryUtil.NULL) return;
        Callbacks.glfwFreeCallbacks(handle);
        GLFW.glfwDestroyWindow(handle);
        window = MemoryUtil.NULL;
    }

    private static GraphicsBackend.Kind transparentBackend(GraphicsBackend.Kind requested) {
        if (requested == GraphicsBackend.Kind.VULKAN) {
            Logger.info("desktop lyric window: using OpenGL because GLFW NO_API/Vulkan "
                    + "windows do not expose portable per-pixel transparency");
            return GraphicsBackend.Kind.GL;
        }
        return requested;
    }

    record FramebufferSize(int width, int height) {
    }
}
