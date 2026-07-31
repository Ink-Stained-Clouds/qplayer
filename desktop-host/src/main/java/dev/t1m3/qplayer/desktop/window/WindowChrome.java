package dev.t1m3.qplayer.desktop.window;

import io.github.timer_err.qml4j.engine.binding.Property;

import org.lwjgl.glfw.GLFW;

/**
 * QML-facing bridge for the custom title bar (Windows only -- registered as
 * the {@code hostWindow} context object solely from {@link DesktopWindow},
 * so Android/mac/Linux QML never sees it; {@code shared-qml} guards on
 * {@code typeof hostWindow !== "undefined"}). Every method is called from
 * QML on the render thread and must marshal the actual GLFW call onto the
 * main thread via {@link DesktopWindow#postMainTask}.
 */
public final class WindowChrome {

    /** Logical-px width of each of the three caption buttons -- single source
     *  of truth shared with {@link WinFrameless}'s hit-test math and (via a
     *  bound Property below) {@code TitleBar.qml}'s own layout, so the two
     *  can never drift out of sync. */
    static final double BUTTON_WIDTH_LOGICAL_PX = 46;
    static final int BUTTON_COUNT = 3;

    public final Property<Boolean> maximized = new Property<>(Boolean.FALSE);
    public final Property<Boolean> focused = new Property<>(Boolean.TRUE);
    public final Property<Double> buttonWidthPx = new Property<>(BUTTON_WIDTH_LOGICAL_PX);

    private final DesktopWindow window;

    WindowChrome(DesktopWindow window) {
        this.window = window;
    }

    /** Plain OS iconify -- matches the native minimize button's existing
     *  behavior exactly (taskbar-minimize, NOT hide-to-tray). */
    public void minimize() {
        window.postMainTask(() -> GLFW.glfwIconifyWindow(window.window()));
    }

    public void toggleMaximize() {
        window.postMainTask(() -> {
            long w = window.window();
            if (GLFW.glfwGetWindowAttrib(w, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
                GLFW.glfwRestoreWindow(w);
            } else {
                GLFW.glfwMaximizeWindow(w);
            }
        });
    }

    /** Reuses the exact same hide-to-tray-if-available-else-quit decision the
     *  native close button has always gone through -- only the trigger path
     *  (a QML click instead of a native WM_CLOSE) is new. */
    public void close() {
        window.postMainTask(window::onExitRequested);
    }
}
