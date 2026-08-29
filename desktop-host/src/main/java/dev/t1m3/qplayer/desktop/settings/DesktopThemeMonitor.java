package dev.t1m3.qplayer.desktop.settings;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
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
        Boolean portalDark = detectPortalDark();
        if (portalDark != null) return portalDark;

        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        String normalizedDesktop = desktop == null ? "" : desktop.toLowerCase(Locale.ROOT);
        String gtkTheme = System.getenv("GTK_THEME");
        if (themeTextIsDark(gtkTheme)) return true;

        if (normalizedDesktop.contains("kde") || normalizedDesktop.contains("plasma")) {
            Boolean backgroundDark = rgbTextIsDark(readKdeWindowBackground());
            if (backgroundDark != null) return backgroundDark;
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

    private static String readKdeWindowBackground() {
        String background = runCommand(
                "kreadconfig6", "--file", "kdeglobals", "--group", "Colors:Window",
                "--key", "BackgroundNormal");
        if (background.isEmpty()) {
            background = runCommand(
                    "kreadconfig5", "--file", "kdeglobals", "--group", "Colors:Window",
                    "--key", "BackgroundNormal");
        }
        return background;
    }

    /** The standard portal returns 1 for dark, 2 for light and 0 for no preference. */
    private static Boolean detectPortalDark() {
        try {
            Pointer connection = PortalApi.GIO.g_bus_get_sync(2, Pointer.NULL, new Pointer[1]);
            if (connection == null) return null;
            Pointer parameters = PortalApi.GLIB.g_variant_parse(Pointer.NULL,
                    "('org.freedesktop.appearance', 'color-scheme')",
                    Pointer.NULL, Pointer.NULL, new Pointer[1]);
            if (parameters == null) {
                PortalApi.GOBJECT.g_object_unref(connection);
                return null;
            }
            Pointer reply = PortalApi.GIO.g_dbus_connection_call_sync(connection,
                    "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
                    "org.freedesktop.portal.Settings", "Read", parameters,
                    Pointer.NULL, 0, 1_000, Pointer.NULL, new Pointer[1]);
            PortalApi.GLIB.g_variant_unref(parameters);
            PortalApi.GOBJECT.g_object_unref(connection);
            if (reply == null) return null;
            Pointer wrapped = PortalApi.GLIB.g_variant_get_child_value(reply, 0);
            Pointer inner = wrapped == null ? null
                    : PortalApi.GLIB.g_variant_get_variant(wrapped);
            // Settings.Read returns a variant and the portal stores the uint32 as
            // another variant value, hence the double angle brackets printed by
            // gdbus: (<<uint32 1>>,).
            Pointer value = inner == null ? null
                    : PortalApi.GLIB.g_variant_get_variant(inner);
            int scheme = value == null ? 0 : PortalApi.GLIB.g_variant_get_uint32(value);
            if (value != null) PortalApi.GLIB.g_variant_unref(value);
            if (inner != null) PortalApi.GLIB.g_variant_unref(inner);
            if (wrapped != null) PortalApi.GLIB.g_variant_unref(wrapped);
            PortalApi.GLIB.g_variant_unref(reply);
            return portalColorSchemeIsDark(scheme);
        } catch (Throwable ignored) {
            return null;
        }
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

    static Boolean portalColorSchemeIsDark(int value) {
        if (value == 1) return true;
        if (value == 2) return false;
        return null;
    }

    static Boolean rgbTextIsDark(String value) {
        if (value == null) return null;
        String[] channels = value.trim().split(",");
        if (channels.length < 3) return null;
        try {
            int red = Integer.parseInt(channels[0].trim());
            int green = Integer.parseInt(channels[1].trim());
            int blue = Integer.parseInt(channels[2].trim());
            if (red < 0 || red > 255 || green < 0 || green > 255
                    || blue < 0 || blue > 255) return null;
            // ITU-R BT.601 luma; 128 gives stable light/dark classification for
            // theme window backgrounds without relying on their arbitrary names.
            return red * 299 + green * 587 + blue * 114 < 128_000;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class PortalApi {
        private static final Gio GIO = Native.load("gio-2.0", Gio.class);
        private static final Glib GLIB = Native.load("glib-2.0", Glib.class);
        private static final GObject GOBJECT = Native.load("gobject-2.0", GObject.class);

        private PortalApi() {
        }

        private interface Gio extends Library {
            Pointer g_bus_get_sync(int busType, Pointer cancellable, Pointer[] error);

            Pointer g_dbus_connection_call_sync(Pointer connection, String busName,
                                                String objectPath, String interfaceName,
                                                String methodName, Pointer parameters,
                                                Pointer replyType, int flags,
                                                int timeoutMillis, Pointer cancellable,
                                                Pointer[] error);
        }

        private interface Glib extends Library {
            Pointer g_variant_parse(Pointer type, String text, Pointer limit,
                                    Pointer endPointer, Pointer[] error);

            Pointer g_variant_get_child_value(Pointer value, long index);

            Pointer g_variant_get_variant(Pointer value);

            int g_variant_get_uint32(Pointer value);

            void g_variant_unref(Pointer value);
        }

        private interface GObject extends Library {
            void g_object_unref(Pointer object);
        }
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
