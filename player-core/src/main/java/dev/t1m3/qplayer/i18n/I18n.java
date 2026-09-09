package dev.t1m3.qplayer.i18n;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

import dev.t1m3.qplayer.util.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The {@code i18n} context global: every user-visible string in QPlayer, by key.
 *
 * <p>No display text is written in QML or Java — both ask for a dotted key such
 * as {@code home.greeting.morning} and get the active language's value. The
 * catalogs are plain {@code key=value} files under {@code /lang}, so adding a
 * language is a file, not a code change.
 *
 * <p>A language is parsed once into a {@link HashMap} and kept there; a lookup
 * is a hash lookup and nothing else, which matters because these run inside QML
 * bindings on the render path. {@link #t(String)} reads {@link #revision}, so a
 * binding that shows a string re-evaluates when the language changes and nothing
 * else has to be reloaded or rebuilt.
 */
public final class I18n extends QObject {

    /** Ships with the app and backs every other language's missing keys. */
    public static final String FALLBACK = "zh_CN";

    private static final I18n INSTANCE = new I18n();

    /** Bumped on every language change; read by {@link #t} so QML re-renders. */
    public final Property<Long> revision = new Property<>(0L);

    private volatile Map<String, String> active = Collections.emptyMap();
    private final Map<String, String> fallback = new HashMap<>();
    private volatile String language = "";

    private I18n() {
        load(FALLBACK, fallback);
        active = fallback;
        language = FALLBACK;
    }

    public static I18n instance() {
        return INSTANCE;
    }

    /** Lookup for Java callers (toasts, notifications, tray menus). */
    public static String tr(String key) {
        return INSTANCE.lookup(key);
    }

    public static String tr(String key, Object... args) {
        return format(INSTANCE.lookup(key), args);
    }

    /** Reactive lookup for QML bindings. */
    public String t(String key) {
        revision.get();
        return lookup(key);
    }

    /** Reactive lookup with {@code {0}}-style placeholders. */
    public String t(String key, Object a) {
        revision.get();
        return format(lookup(key), a);
    }

    public String t(String key, Object a, Object b) {
        revision.get();
        return format(lookup(key), a, b);
    }

    /** Active language tag, e.g. {@code zh_CN}. */
    public String language() {
        return language;
    }

    /**
     * Switches language. An unknown or unreadable catalog keeps the current one,
     * so a bad value can never leave the UI without strings.
     */
    public void setLanguage(String tag) {
        String next = normalize(tag);
        if (next.equals(language)) return;
        Map<String, String> loaded;
        if (FALLBACK.equals(next)) {
            loaded = fallback;
        } else {
            Map<String, String> values = new HashMap<>();
            if (!load(next, values)) return;
            loaded = values;
        }
        active = loaded;
        language = next;
        revision.set(revision.peek() + 1);
    }

    /** The language the platform locale asks for, if QPlayer has it. */
    public static String systemLanguage() {
        Locale locale = Locale.getDefault();
        return "zh".equalsIgnoreCase(locale.getLanguage()) ? "zh_CN" : "en_US";
    }

    private String lookup(String key) {
        if (key == null || key.isEmpty()) return "";
        String value = active.get(key);
        if (value == null) value = fallback.get(key);
        // Showing the key beats showing nothing: a missing string stays visible
        // in the UI and in screenshots instead of silently collapsing a row.
        return value != null ? value : key;
    }

    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        String out = pattern;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
        }
        return out;
    }

    private static String normalize(String tag) {
        String value = tag != null ? tag.trim() : "";
        return value.isEmpty() ? FALLBACK : value;
    }

    private static boolean load(String tag, Map<String, String> into) {
        String resource = "/lang/" + tag + ".properties";
        try (InputStream input = I18n.class.getResourceAsStream(resource)) {
            if (input == null) {
                Logger.warn("language catalog missing: {}", resource);
                return false;
            }
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            for (String key : properties.stringPropertyNames()) {
                into.put(key, properties.getProperty(key));
            }
            return true;
        } catch (Exception error) {
            Logger.warn("language catalog {} failed to load: {}", tag, error.toString());
            return false;
        }
    }
}
