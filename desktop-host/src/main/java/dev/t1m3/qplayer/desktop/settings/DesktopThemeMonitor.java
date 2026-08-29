package dev.t1m3.qplayer.desktop.settings;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import dev.t1m3.qplayer.util.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Cross-platform system light/dark detector with a small live polling loop. */
public final class DesktopThemeMonitor implements AutoCloseable {

    private static final String WINDOWS_PERSONALIZE =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final long POLL_MILLIS = 1_500L;

    private final Consumer<Boolean> listener;
    private volatile boolean running;
    private boolean lastDark;
    private Thread thread;

    public DesktopThemeMonitor(boolean initialDark, Consumer<Boolean> listener) {
        this.lastDark = initialDark;
        this.listener = listener;
    }

    /** Detect the current application appearance without initializing AWT/Swing. */
    public static boolean detectSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return detectWindowsDark();
        if (os.contains("mac")) return detectMacDark();
        return detectLinuxDark();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::poll, "qplayer-system-theme");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void close() {
        running = false;
        Thread current = thread;
        if (current == null) return;
        current.interrupt();
        try {
            current.join(2_000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    private void poll() {
        while (running) {
            try {
                boolean dark = detectSystemDark();
                if (dark != lastDark) {
                    lastDark = dark;
                    listener.accept(dark);
                }
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                // A transient desktop-service failure must not terminate live
                // following. Avoid log spam by waiting until the next poll.
                Logger.warn("system theme probe failed: {}", error.toString());
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static boolean detectWindowsDark() {
        try {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER,
                    WINDOWS_PERSONALIZE, "AppsUseLightTheme")) return false;
            int light = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
                    WINDOWS_PERSONALIZE, "AppsUseLightTheme");
            return windowsAppsUseLightThemeIsDark(light);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean detectMacDark() {
        return themeTextIsDark(runCommand("defaults", "read", "-g", "AppleInterfaceStyle"));
    }

    private static boolean detectLinuxDark() {
        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        String normalizedDesktop = desktop == null ? "" : desktop.toLowerCase(Locale.ROOT);
        String gtkTheme = System.getenv("GTK_THEME");
        if (themeTextIsDark(gtkTheme)) return true;

        if (normalizedDesktop.contains("kde") || normalizedDesktop.contains("plasma")) {
            return themeTextIsDark(readKdeColorScheme());
        }

        String colorScheme = runCommand(
                "gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (themeTextIsDark(colorScheme)) return true;
        if (themeTextIsExplicitLight(colorScheme)) return false;

        String gnomeTheme = runCommand(
                "gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
        if (themeTextIsDark(gnomeTheme)) return true;
        if (!colorScheme.isEmpty() || normalizedDesktop.contains("gnome")
                || normalizedDesktop.contains("unity")
                || normalizedDesktop.contains("cinnamon")) return false;

        // KDE 6 and KDE 5 expose the active scheme through the same config key,
        // but ship different command names.
        return themeTextIsDark(readKdeColorScheme());
    }

    private static String readKdeColorScheme() {
        String kdeScheme = runCommand(
                "kreadconfig6", "--file", "kdeglobals", "--group", "General",
                "--key", "ColorScheme");
        if (kdeScheme.isEmpty()) {
            kdeScheme = runCommand(
                    "kreadconfig5", "--file", "kdeglobals", "--group", "General",
                    "--key", "ColorScheme");
        }
        return kdeScheme;
    }

    static boolean windowsAppsUseLightThemeIsDark(int value) {
        return value == 0;
    }

    static boolean themeTextIsDark(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("dark")
                || normalized.contains("prefer-dark")
                || normalized.contains("dark");
    }

    static boolean themeTextIsExplicitLight(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("prefer-light");
    }

    private static String runCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) return "";
            try (InputStream input = process.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
