package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;

import java.util.List;

/** Native window-manager hints for non-document auxiliary windows. */
final class AuxiliaryWindowStyle {

    // Not all extended-style constants are exposed by jna-platform's WinUser.
    static final int WS_EX_TOOLWINDOW = 0x00000080;
    static final int WS_EX_APPWINDOW = 0x00040000;
    private static final int SHAPE_SET = 0;
    private static final int SHAPE_INPUT = 2;
    private static final int UNSORTED = 0;

    private AuxiliaryWindowStyle() {
    }

    /**
     * Apply before every show so no taskbar/switcher entry flashes briefly.
     * Some XWayland window managers re-evaluate an unmapped window when its main
     * application window disappears, so creation-time hints alone are not enough.
     */
    static void hideFromTaskSwitchers(long glfwWindow) {
        try {
            int platform = GLFW.glfwGetPlatform();
            if (platform == GLFW.GLFW_PLATFORM_WIN32) {
                applyWindows(glfwWindow);
            } else if (platform == GLFW.GLFW_PLATFORM_X11) {
                applyX11(glfwWindow);
            }
            // macOS exposes one Dock/Cmd-Tab entry per application, not per
            // NSWindow. The lyric surface therefore has no independent entry to
            // suppress. Native Wayland offers no portable equivalent; desktop
            // lyrics are already disabled there because they cannot be moved.
        } catch (Throwable error) {
            Logger.warn("desktop lyric task-switcher hint failed: {}", error.toString());
        }
    }

    static int windowsToolStyle(int currentStyle) {
        return (currentStyle | WS_EX_TOOLWINDOW) & ~WS_EX_APPWINDOW;
    }

    /**
     * On X11/XWayland, keep only the unlock button in the input shape while the
     * lyric window is locked. Unlike GLFW's whole-window passthrough attribute,
     * the X Shape extension lets that one rectangle continue receiving clicks.
     */
    static boolean setX11InputRegion(long glfwWindow, boolean locked,
                                     int unlockX, int unlockY,
                                     int unlockWidth, int unlockHeight,
                                     int windowWidth, int windowHeight) {
        if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_X11) return false;
        try {
            long displayHandle = GLFWNativeX11.glfwGetX11Display();
            long windowHandle = GLFWNativeX11.glfwGetX11Window(glfwWindow);
            if (displayHandle == 0L || windowHandle == 0L) return false;
            Pointer display = Pointer.createConstant(displayHandle);
            if (XextApi.I.XShapeQueryExtension(display,
                    new IntByReference(), new IntByReference()) == 0) return false;

            XRectangle rectangle = locked
                    ? new XRectangle(unlockX, unlockY, unlockWidth, unlockHeight)
                    : new XRectangle(0, 0, windowWidth, windowHeight);
            rectangle.write();
            XextApi.I.XShapeCombineRectangles(display, new NativeLong(windowHandle),
                    SHAPE_INPUT, 0, 0, rectangle, 1, SHAPE_SET, UNSORTED);
            X11Api.I.XFlush(display);
            return true;
        } catch (Throwable error) {
            Logger.warn("desktop lyric X11 input region failed: {}", error.toString());
            return false;
        }
    }

    private static void applyWindows(long glfwWindow) {
        long nativeHandle = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        if (nativeHandle == 0L) return;
        WinDef.HWND hwnd = new WinDef.HWND(Pointer.createConstant(nativeHandle));
        int current = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, windowsToolStyle(current));
        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER
                        | WinUser.SWP_FRAMECHANGED);
    }

    private static void applyX11(long glfwWindow) {
        long displayHandle = GLFWNativeX11.glfwGetX11Display();
        long windowHandle = GLFWNativeX11.glfwGetX11Window(glfwWindow);
        if (displayHandle == 0L || windowHandle == 0L) return;

        Pointer display = Pointer.createConstant(displayHandle);
        NativeLong window = new NativeLong(windowHandle);
        NativeLong atomType = X11Api.I.XInternAtom(display, "ATOM", 0);
        NativeLong stateProperty = X11Api.I.XInternAtom(display, "_NET_WM_STATE", 0);
        NativeLong skipTaskbar = X11Api.I.XInternAtom(
                display, "_NET_WM_STATE_SKIP_TASKBAR", 0);
        NativeLong skipPager = X11Api.I.XInternAtom(
                display, "_NET_WM_STATE_SKIP_PAGER", 0);
        NativeLong above = X11Api.I.XInternAtom(
                display, "_NET_WM_STATE_ABOVE", 0);
        NativeLong windowTypeProperty = X11Api.I.XInternAtom(
                display, "_NET_WM_WINDOW_TYPE", 0);
        NativeLong dockType = X11Api.I.XInternAtom(
                display, "_NET_WM_WINDOW_TYPE_DOCK", 0);
        NativeLong utilityType = X11Api.I.XInternAtom(
                display, "_NET_WM_WINDOW_TYPE_UTILITY", 0);

        // GLFW_FLOATING initially asks for ABOVE. Preserve it when replacing the
        // complete _NET_WM_STATE property with our task-switcher hints.
        try (Memory states = nativeLongArray(skipTaskbar, skipPager, above);
             // Plasma Wayland may still expose a persistent XWayland UTILITY in
             // its task manager. A dock without struts is the appropriate overlay
             // layer: excluded from taskbars/switchers and kept above normal
             // windows. UTILITY remains the ordered fallback for other WMs.
             Memory type = nativeLongArray(dockType, utilityType)) {
            X11Api.I.XChangeProperty(display, window, stateProperty, atomType,
                    32, 0, states, 3);
            X11Api.I.XChangeProperty(display, window, windowTypeProperty, atomType,
                    32, 0, type, 2);
        }
        // Wait until XWayland has accepted the properties before GLFW sends the
        // map request. XFlush alone permits KWin to observe the map first and add
        // a transient task-manager entry when the main player is hidden.
        X11Api.I.XSync(display, 0);
    }

    private static Memory nativeLongArray(NativeLong... values) {
        Memory memory = new Memory((long) NativeLong.SIZE * values.length);
        for (int i = 0; i < values.length; i++) {
            memory.setNativeLong((long) i * NativeLong.SIZE, values[i]);
        }
        return memory;
    }

    private interface X11Api extends Library {
        X11Api I = Native.load("X11", X11Api.class);

        NativeLong XInternAtom(Pointer display, String atomName, int onlyIfExists);

        int XChangeProperty(Pointer display, NativeLong window, NativeLong property,
                            NativeLong type, int format, int mode, Pointer data,
                            int elementCount);

        int XFlush(Pointer display);

        int XSync(Pointer display, int discard);
    }

    private interface XextApi extends Library {
        XextApi I = Native.load("Xext", XextApi.class);

        int XShapeQueryExtension(Pointer display, IntByReference eventBase,
                                 IntByReference errorBase);

        void XShapeCombineRectangles(Pointer display, NativeLong window,
                                     int destinationKind, int xOffset, int yOffset,
                                     XRectangle rectangles, int rectangleCount,
                                     int operation, int ordering);
    }

    public static final class XRectangle extends Structure {
        public short x;
        public short y;
        public short width;
        public short height;

        public XRectangle() {
        }

        XRectangle(int x, int y, int width, int height) {
            this.x = (short) x;
            this.y = (short) y;
            this.width = (short) width;
            this.height = (short) height;
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("x", "y", "width", "height");
        }
    }
}
