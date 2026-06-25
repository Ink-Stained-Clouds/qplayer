package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.input.TextEditable;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;

import org.lwjgl.glfw.GLFW;

/**
 * Translates GLFW input (fired on the main thread) into qml4j dispatch calls, by
 * marshalling each event onto the render thread — the desktop analogue of the
 * Android view's {@code queueEvent(...)}. Pointer coordinates are passed in
 * logical units: under the standard framebuffer = window×contentScale relation,
 * the QML root (sized {@code framebuffer / uiScale}) matches GLFW's window/screen
 * coordinate space, so cursor positions map 1:1.
 *
 * <p>Owns the host-drawn lyric column's drag gesture (grab / slop / scroll /
 * tap-to-seek), mirroring the Android shell; all gesture state is mutated only
 * inside the posted render-thread tasks, so it needs no synchronization.
 */
final class InputBridge {

    private static final float LYRIC_TAP_SLOP = 12f;

    private final DesktopWindow win;

    // Pixels of scroll per wheel notch (the eased target distance), and the
    // per-frame easing fraction toward the remaining target.
    private static final float SCROLL_STEP = 64f;
    private static final float SCROLL_EASE = 0.22f;

    private volatile double cursorX;
    private volatile double cursorY;

    // Smooth-scroll accumulators (render-thread only: written via posted render
    // tasks, animated in tickScroll). The raw GLFW wheel is discrete, so easing the
    // remaining distance toward 0 each frame turns it into a smooth glide.
    private double pendingScrollX;
    private double pendingScrollY;

    // Lyric-body gesture state (render-thread only).
    private boolean lyGrab;
    private boolean lyMoved;
    private float lyDownY;

    InputBridge(DesktopWindow win) {
        this.win = win;
    }

    void install(long window) {
        GLFW.glfwSetCursorPosCallback(window, (w, x, y) -> {
            cursorX = x;
            cursorY = y;
            final float fx = (float) x, fy = (float) y;
            win.postRenderTask(() -> onMove(fx, fy));
        });
        GLFW.glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            final float fx = (float) cursorX, fy = (float) cursorY;
            if (action == GLFW.GLFW_PRESS) win.postRenderTask(() -> onPress(fx, fy));
            else if (action == GLFW.GLFW_RELEASE) win.postRenderTask(() -> onRelease(fx, fy));
        });
        GLFW.glfwSetScrollCallback(window, (w, dx, dy) -> {
            final double adx = dx * SCROLL_STEP, ady = dy * SCROLL_STEP;
            // Accumulate the target on the render thread; tickScroll() eases it out.
            win.postRenderTask(() -> {
                pendingScrollX += adx;
                pendingScrollY += ady;
            });
        });
        GLFW.glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (action == GLFW.GLFW_REPEAT) return;
            final boolean down = action == GLFW.GLFW_PRESS;
            final int code = mapKey(key, mods);
            if (code == 0) return;
            final boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
            win.postRenderTask(() -> {
                QmlView v = win.view();
                if (v != null) v.dispatchKey(code, null, down, shift);
            });
        });
        GLFW.glfwSetCharCallback(window, (w, codepoint) -> {
            final String s = new String(Character.toChars(codepoint));
            win.postRenderTask(() -> {
                QmlView v = win.view();
                if (v != null && !s.isEmpty()) v.dispatchKey(0, s, true);
            });
        });
    }

    /** Called once per frame on the render thread: glide the wheel scroll toward the
     *  accumulated target with exponential easing, so each notch animates instead of
     *  snapping. */
    void tickScroll() {
        if (Math.abs(pendingScrollX) < 0.5 && Math.abs(pendingScrollY) < 0.5) {
            pendingScrollX = pendingScrollY = 0;
            return;
        }
        QmlView v = win.view();
        if (v == null) return;
        double stepX = pendingScrollX * SCROLL_EASE;
        double stepY = pendingScrollY * SCROLL_EASE;
        // Ensure the tail finishes promptly rather than asymptotically crawling.
        if (Math.abs(pendingScrollX - stepX) < 0.5) stepX = pendingScrollX;
        if (Math.abs(pendingScrollY - stepY) < 0.5) stepY = pendingScrollY;
        pendingScrollX -= stepX;
        pendingScrollY -= stepY;
        v.dispatchWheel((float) cursorX, (float) cursorY, (float) stepX, (float) stepY);
    }

    // --- render-thread handlers ----------------------------------------------

    private void onPress(float x, float y) {
        QmlView v = win.view();
        if (v == null) return;
        float topInset = win.settings() != null ? win.settings().topInset() : 0f;
        float surfaceHLogical = win.framebufferSize()[1] / win.uiScale();
        LyricCompositor c = win.compositor();
        // The lyric body (between the QML title and transport bands) is host-drawn
        // with no QML controls under it: a drag scrolls, a tap seeks. Wait for slop
        // before engaging the scroll so a tap stays a tap.
        if (c.lyricsScrollable(y, surfaceHLogical, topInset)) {
            lyGrab = true;
            lyDownY = y;
            lyMoved = false;
            return;
        }
        lyGrab = false;
        v.dispatchPointerDown(x, y);
    }

    private void onMove(float x, float y) {
        if (lyGrab) {
            LyricCompositor c = win.compositor();
            if (!lyMoved) {
                if (Math.abs(y - lyDownY) > LYRIC_TAP_SLOP) {
                    lyMoved = true;
                    c.lyricRenderer().scrollDown(lyDownY);
                    c.lyricRenderer().scrollMove(y);
                }
            } else {
                c.lyricRenderer().scrollMove(y);
            }
            return;
        }
        QmlView v = win.view();
        if (v != null) v.dispatchPointerMove(x, y);
    }

    private void onRelease(float x, float y) {
        if (lyGrab) {
            lyGrab = false;
            LyricCompositor c = win.compositor();
            PlayerController controller = win.controller();
            if (lyMoved) {
                c.lyricRenderer().scrollUp();
            } else {
                long t = c.lyricRenderer().timeAtScreenY(lyDownY);
                if (t >= 0L && controller != null) controller.seek(t);
            }
            return;
        }
        QmlView v = win.view();
        if (v != null) v.dispatchPointerUp(x, y);
    }

    // Printable characters arrive via the char callback; this maps only the control
    // keys QmlView understands. 0 means "not a control key" -> ignored.
    private static int mapKey(int key, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE: return QmlView.KEY_BACKSPACE;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER: return QmlView.KEY_ENTER;
            case GLFW.GLFW_KEY_LEFT: return QmlView.KEY_LEFT;
            case GLFW.GLFW_KEY_RIGHT: return QmlView.KEY_RIGHT;
            case GLFW.GLFW_KEY_UP: return QmlView.KEY_UP;
            case GLFW.GLFW_KEY_DOWN: return QmlView.KEY_DOWN;
            case GLFW.GLFW_KEY_HOME: return QmlView.KEY_HOME;
            case GLFW.GLFW_KEY_END: return QmlView.KEY_END;
            case GLFW.GLFW_KEY_ESCAPE: return QmlView.KEY_ESCAPE;
            case GLFW.GLFW_KEY_TAB:
                return (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? QmlView.KEY_BACKTAB : QmlView.KEY_TAB;
            default: return 0;
        }
    }
}
