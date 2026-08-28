package dev.t1m3.qplayer.desktop.app;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import javax.swing.Timer;
import java.awt.Window;

/** Brings ownerless Swing windows in front of QPlayer's GLFW main window. */
final class DesktopSwingFocus {
    private static final int SW_RESTORE = 9;

    private DesktopSwingFocus() {}

    static void show(Window window) {
        window.setVisible(true);
        requestForeground(window);
    }

    static void requestForeground(Window window) {
        if (window == null || !window.isDisplayable()) return;
        window.toFront();
        window.requestFocus();

        if (!isWindows()) return;

        // GLFW's native main window cannot be an AWT owner. Windows therefore
        // tends to insert the first ownerless Swing window behind it. A short
        // topmost pulse makes the user-initiated window visible immediately; the
        // flag is removed after activation so it does not stay above other apps.
        boolean wasAlwaysOnTop = window.isAlwaysOnTop();
        try {
            window.setAlwaysOnTop(true);
        } catch (SecurityException ignored) {
        }
        activateNativeWindow(window);

        Timer releaseTopmost = new Timer(200, event -> {
            if (!window.isDisplayable()) return;
            try {
                window.setAlwaysOnTop(wasAlwaysOnTop);
            } catch (SecurityException ignored) {
            }
            window.toFront();
            window.requestFocus();
            activateNativeWindow(window);
        });
        releaseTopmost.setRepeats(false);
        releaseTopmost.start();
    }

    private static void activateNativeWindow(Window window) {
        try {
            Pointer pointer = Native.getComponentPointer(window);
            if (pointer == null) return;
            HWND handle = new HWND(pointer);
            User32.INSTANCE.ShowWindow(handle, SW_RESTORE);
            User32.INSTANCE.BringWindowToTop(handle);
            User32.INSTANCE.SetForegroundWindow(handle);
        } catch (Throwable ignored) {
            // The topmost pulse and ordinary AWT focus calls remain as fallback.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
