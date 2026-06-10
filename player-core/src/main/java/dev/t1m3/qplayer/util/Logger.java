package dev.t1m3.qplayer.util;

import java.util.logging.Level;

/**
 * Minimal SLF4J-style logger ({@code {}} placeholders) backed by
 * {@code java.util.logging}. Mirrors the subset of the Haedus logger API used
 * by the migrated netease / lyric sources so those files copy over unchanged.
 */
public final class Logger {

    private static final java.util.logging.Logger DELEGATE =
            java.util.logging.Logger.getLogger("musicplayer");

    private Logger() {
    }

    public static void info(String str, Object... o) {
        DELEGATE.log(Level.INFO, format(str, o));
    }

    public static void warn(String str, Object... o) {
        DELEGATE.log(Level.WARNING, format(str, o));
    }

    public static void error(String str, Object... o) {
        DELEGATE.log(Level.SEVERE, format(str, o));
    }

    public static void success(String str, Object... o) {
        DELEGATE.log(Level.INFO, format(str, o));
    }

    public static void exception(Throwable ex) {
        DELEGATE.log(Level.SEVERE, ex.getMessage(), ex);
    }

    private static String format(String str, Object... o) {
        if (str == null || o == null || o.length == 0) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        int arg = 0;
        int i = 0;
        while (i < str.length()) {
            if (arg < o.length && i + 1 < str.length()
                    && str.charAt(i) == '{' && str.charAt(i + 1) == '}') {
                sb.append(String.valueOf(o[arg++]));
                i += 2;
            } else {
                sb.append(str.charAt(i++));
            }
        }
        return sb.toString();
    }
}
