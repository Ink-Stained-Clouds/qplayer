package dev.t1m3.qplayer.desktop.window;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.RRect;

import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.settings.SettingsStore;
import dev.t1m3.qplayer.util.Logger;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Desktop lyrics (issue #25): a small, undecorated, always-on-top, draggable
 * window separate from the main app window, showing the current lyric line
 * (or the track title/artist when there's no synced lyric at this position).
 *
 * <p>Deliberately NOT QML — its own independent GL context + a hand-drawn
 * Skija Canvas, the same host-drawn approach the main lyric page's word-level
 * renderer already uses ({@code LyricCompositor}/{@code LyricRenderer}), just
 * far simpler (one plain line, no per-syllable timing). A second qml4j
 * {@code QmlView} would be much heavier for what this needs, and mixing this
 * window's backend with the main window's Vulkan-or-GL choice would need its
 * own adapter either way — this window is always plain GL regardless of which
 * the main window uses, so the two never interact.
 *
 * <p><b>Threading:</b> {@link #renderFrame} runs on the RENDER thread, called
 * by {@link RenderThread} once per main-window frame, right after the main
 * window's own present() — NOT a second thread. {@link PlayerController}'s
 * {@code Property} fields are plain (non-volatile) reads, safe only from the
 * thread that already writes them (render thread, via post()/pump()); a
 * dedicated second thread reading {@code player.lyrics}/{@code positionMs}
 * directly would be a data race. GLFW window creation/show/hide/drag,
 * conversely, must run on the process main thread like any other GLFW call —
 * {@link #create} and {@link #setEnabled} are main-thread-only.
 *
 * <p><b>Platform coverage:</b> only verified on Windows (this project's dev
 * environment). {@code GLFW_TRANSPARENT_FRAMEBUFFER} support varies by
 * platform/compositor (Linux depends on the WM actually compositing; macOS is
 * untested) — {@link #draw} always paints a translucent pill behind the text
 * regardless, so the window still reads correctly even where true framebuffer
 * transparency doesn't land, just with a visible (not fully invisible)
 * backdrop.
 */
public final class DesktopLyricWindow {

    private static final String ENABLED_KEY = "desktopLyricEnabled";
    private static final String X_KEY = "desktopLyricX";
    private static final String Y_KEY = "desktopLyricY";
    private static final int WIDTH = 720;
    private static final int HEIGHT = 92;
    private static final float BASE_FONT_SIZE = 26f;
    private static final float MIN_FONT_SIZE = 15f;
    private static final float SIDE_PADDING = 32f;

    private final SettingsStore store;
    private long window = MemoryUtil.NULL;
    private boolean glReady;
    private DirectContext context;
    private BackendRenderTarget target;
    private Surface surface;
    private final Paint bgPaint = new Paint().setAntiAlias(true).setColor(0x99000000);
    private final Paint textPaint = new Paint().setAntiAlias(true).setColor(0xFFFFFFFF);

    private volatile boolean enabled;
    private boolean dragging;
    private double dragCursorX0, dragCursorY0;
    private int dragWinX0, dragWinY0;

    public DesktopLyricWindow(SettingsStore store) {
        this.store = store;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Main thread only, once, after the main window exists (GLFW window
     *  hints are process-global and sticky, so this resets and re-sets them
     *  from scratch rather than inheriting whatever the main window left). */
    public void create() {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "QPlayer Lyrics", MemoryUtil.NULL, MemoryUtil.NULL);
        // Reset hints back to default so the NEXT glfwCreateWindow call anywhere
        // else in the process (e.g. a Vulkan-fallback recreate of the main
        // window) doesn't inherit this window's decorated/floating/transparent
        // hints by accident.
        GLFW.glfwDefaultWindowHints();
        if (window == MemoryUtil.NULL) {
            Logger.warn("desktop lyric window: glfwCreateWindow failed");
            return;
        }
        int x = store.getInt(X_KEY, -1);
        int y = store.getInt(Y_KEY, -1);
        if (x >= 0 && y >= 0) {
            GLFW.glfwSetWindowPos(window, x, y);
        } else {
            centerBottom();
        }
        installDragHandlers();
        enabled = store.getBool(ENABLED_KEY, false);
        if (enabled) GLFW.glfwShowWindow(window);
    }

    private void centerBottom() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) return;
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode == null) return;
        GLFW.glfwSetWindowPos(window, (mode.width() - WIDTH) / 2, mode.height() - HEIGHT - 96);
    }

    private void installDragHandlers() {
        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == GLFW.GLFW_PRESS) {
                dragging = true;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    DoubleBuffer cx = stack.mallocDouble(1);
                    DoubleBuffer cy = stack.mallocDouble(1);
                    GLFW.glfwGetCursorPos(win, cx, cy);
                    dragCursorX0 = cx.get(0);
                    dragCursorY0 = cy.get(0);
                    IntBuffer wx = stack.mallocInt(1);
                    IntBuffer wy = stack.mallocInt(1);
                    GLFW.glfwGetWindowPos(win, wx, wy);
                    dragWinX0 = wx.get(0);
                    dragWinY0 = wy.get(0);
                }
            } else if (action == GLFW.GLFW_RELEASE && dragging) {
                dragging = false;
                persistPosition();
            }
        });
        GLFW.glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (!dragging) return;
            int newX = (int) Math.round(dragWinX0 + (xpos - dragCursorX0));
            int newY = (int) Math.round(dragWinY0 + (ypos - dragCursorY0));
            GLFW.glfwSetWindowPos(win, newX, newY);
        });
    }

    private void persistPosition() {
        if (window == MemoryUtil.NULL) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer wx = stack.mallocInt(1);
            IntBuffer wy = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(window, wx, wy);
            store.putInt(X_KEY, wx.get(0));
            store.putInt(Y_KEY, wy.get(0));
        }
    }

    /** Main thread only. */
    public void setEnabled(boolean on) {
        if (window == MemoryUtil.NULL) return;
        enabled = on;
        store.putBool(ENABLED_KEY, on);
        if (on) GLFW.glfwShowWindow(window);
        else GLFW.glfwHideWindow(window);
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Render thread, once per main-window frame, right after its own
     *  present(). No-op (and no context switch at all) unless actually
     *  enabled.
     *
     * <p>{@code mainWindowHasGlContext}: false when the main window is
     * running Vulkan ({@code GLFW_CLIENT_API = GLFW_NO_API}, no GL context at
     * all) -- restoring a GL context onto a NO_API window is a GLFW error
     * (harmless -- this app's error callback just prints -- but noisy every
     * single frame). True (GL main window) skips nothing: GLBackend binds its
     * context once in init() and never again, so leaving this thread's
     * current context on the lyric window instead would silently break every
     * subsequent main-window draw call. */
    public void renderFrame(long mainWindow, boolean mainWindowHasGlContext, PlayerController controller) {
        if (!enabled || window == MemoryUtil.NULL) return;
        GLFW.glfwMakeContextCurrent(window);
        if (!glReady) {
            GL.createCapabilities();
            context = DirectContext.makeGL();
            rebuildSurface();
            glReady = true;
        }
        Canvas canvas = surface.getCanvas();
        canvas.clear(0x00000000);
        draw(canvas, controller);
        context.flushAndSubmit(surface);
        GLFW.glfwSwapBuffers(window);
        if (mainWindowHasGlContext) GLFW.glfwMakeContextCurrent(mainWindow);
    }

    private void rebuildSurface() {
        if (surface != null) surface.close();
        if (target != null) target.close();
        target = BackendRenderTarget.makeGL(WIDTH, HEIGHT, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8);
        surface = Surface.wrapBackendRenderTarget(context, target,
                SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB());
    }

    private void draw(Canvas canvas, PlayerController controller) {
        String line = currentLine(controller);
        if (line == null || line.isEmpty()) return;

        canvas.drawRRect(RRect.makePillXYWH(0, 0, WIDTH, HEIGHT), bgPaint);

        float maxTextWidth = WIDTH - SIDE_PADDING * 2;
        float size = BASE_FONT_SIZE;
        Font font = Fonts.get(Fonts.Weight.MEDIUM, size);
        float textWidth = font.measureTextWidth(line);
        if (textWidth > maxTextWidth) {
            // One-shot proportional shrink (no iterative re-measure loop): good
            // enough for a single short line, and keeps this a single Fonts.get
            // call in the common case instead of the same every frame.
            size = Math.max(MIN_FONT_SIZE, size * (maxTextWidth / textWidth));
            font = Fonts.get(Fonts.Weight.MEDIUM, size);
            textWidth = font.measureTextWidth(line);
        }
        float x = (WIDTH - textWidth) / 2f;
        float baseline = HEIGHT / 2f - (font.getMetrics().getAscent() + font.getMetrics().getDescent()) / 2f;
        canvas.drawString(line, x, baseline, font, textPaint);
    }

    /** The lyric line active at the current playback position, or the track's
     *  title/artist as a fallback when there's no synced lyric there (an
     *  instrumental intro/outro, or a track with no lyrics at all) — the
     *  window is never just blank while something is actually playing. */
    private String currentLine(PlayerController controller) {
        List<LyricLine> lines = controller.lyrics.peek();
        Long posBoxed = controller.positionMs.peek();
        long pos = posBoxed != null ? posBoxed : 0L;
        if (lines != null && !lines.isEmpty()) {
            String best = null;
            for (LyricLine l : lines) {
                if (l.startMs() <= pos) best = l.text();
                else break;
            }
            if (best != null && !best.trim().isEmpty()) return best;
        }
        String title = controller.title.peek();
        if (title == null || title.isEmpty()) return null;
        String artist = controller.artist.peek();
        return artist != null && !artist.isEmpty() ? title + " - " + artist : title;
    }

    /** Safe to call from the main thread at final shutdown (after the render
     *  thread has already stopped and joined, so nothing else holds this
     *  window's GL context current) — binds it here first rather than
     *  requiring the caller to already be on whatever thread last rendered. */
    public void disposeGpu() {
        if (window == MemoryUtil.NULL) return;
        if (glReady) GLFW.glfwMakeContextCurrent(window);
        if (surface != null) { surface.close(); surface = null; }
        if (target != null) { target.close(); target = null; }
        if (context != null) { context.close(); context = null; }
        glReady = false;
        GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
    }

    public void disposeWindow() {
        if (window == MemoryUtil.NULL) return;
        Callbacks.glfwFreeCallbacks(window);
        GLFW.glfwDestroyWindow(window);
        window = MemoryUtil.NULL;
    }
}
