package dev.t1m3.qplayer.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import dev.t1m3.qplayer.settings.SettingsStore;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Desktop half of the settings framework: a JSON file at
 * {@code <AppDirs.base()>/settings.json}. Everything else about settings —
 * which ones exist, their defaults, what they do, how they're rendered — lives
 * in {@code player-core}'s {@code dev.t1m3.qplayer.settings}, shared with
 * Android (which stores the same keys in SharedPreferences).
 */
public final class JsonSettingsStore implements SettingsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private JsonObject store;

    public JsonSettingsStore() {
        this.file = new File(AppDirs.base(), "settings.json");
        this.store = read(file);
    }

    @Override
    public boolean getBool(String key, boolean def) {
        try {
            return store.has(key) ? store.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public int getInt(String key, int def) {
        try {
            return store.has(key) ? store.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public String getString(String key, String def) {
        try {
            return store.has(key) ? store.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public boolean has(String key) {
        return store.has(key);
    }

    @Override
    public void putBool(String key, boolean value) {
        store.addProperty(key, value);
        persist();
    }

    @Override
    public void putInt(String key, int value) {
        store.addProperty(key, value);
        persist();
    }

    @Override
    public void putString(String key, String value) {
        store.addProperty(key, value);
        persist();
    }

    private static JsonObject read(File f) {
        try {
            if (f.isFile()) {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JsonObject o = GSON.fromJson(json, JsonObject.class);
                if (o != null) return o;
            }
        } catch (Exception e) {
            Logger.warn("settings read failed: {}", e);
        }
        return new JsonObject();
    }

    private void persist() {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            Files.write(file.toPath(), GSON.toJson(store).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.warn("settings write failed: {}", e);
        }
    }
}
