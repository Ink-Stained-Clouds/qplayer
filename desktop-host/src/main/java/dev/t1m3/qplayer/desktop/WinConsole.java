package dev.t1m3.qplayer.desktop;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Windows console control. The native image is a console-subsystem binary, so
 * Windows hands it a console; when double-clicked from Explorer that console is a
 * fresh window owned by us alone (the black box the user sees). Detach it in that
 * case. When the binary is launched from an existing terminal the console is shared
 * with the shell, so we keep it and the log4j2 console output stays visible — i.e.
 * run from a terminal to watch logs, double-click for a clean GUI.
 */
final class WinConsole {

    interface K32 extends StdCallLibrary {
        K32 I = Native.load("kernel32", K32.class, W32APIOptions.DEFAULT_OPTIONS);

        // Fills processList with the PIDs attached to the current console and returns
        // their count (the real count even if the buffer is smaller).
        int GetConsoleProcessList(int[] processList, int processCount);
        boolean FreeConsole();
    }

    private WinConsole() {}

    /** Detach the console iff this process is its sole owner (double-clicked). */
    static void detachIfStandalone() {
        try {
            int[] pids = new int[2];
            int n = K32.I.GetConsoleProcessList(pids, pids.length);
            if (n <= 1) {
                K32.I.FreeConsole();
            }
        } catch (Throwable ignored) {
            // No console / API unavailable — nothing to detach.
        }
    }
}
