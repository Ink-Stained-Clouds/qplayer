package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;

/** Native window-manager hints for non-document auxiliary windows. */
final class AuxiliaryWindowStyle {

    // Not all extended-style constants are exposed by jna-platform's WinUser.
    static final int WS_EX_TOOLWINDOW = 0x00000080;
    static final int WS_EX_APPWINDOW = 0x00040000;

    private AuxiliaryWindowStyle() {
    }

    /** Apply before the first show so no taskbar/switcher entry flashes briefly. */
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
        NativeLong windowTypeProperty = X11Api.I.XInternAtom(
                display, "_NET_WM_WINDOW_TYPE", 0);
        NativeLong utilityType = X11Api.I.XInternAtom(
                display, "_NET_WM_WINDOW_TYPE_UTILITY", 0);

        try (Memory states = nativeLongArray(skipTaskbar, skipPager);
             Memory type = nativeLongArray(utilityType)) {
            X11Api.I.XChangeProperty(display, window, stateProperty, atomType,
                    32, 0, states, 2);
            X11Api.I.XChangeProperty(display, window, windowTypeProperty, atomType,
                    32, 0, type, 1);
        }
        X11Api.I.XFlush(display);
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
    }
}
