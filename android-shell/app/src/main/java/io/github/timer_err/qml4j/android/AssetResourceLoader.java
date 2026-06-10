package io.github.timer_err.qml4j.android;

import android.content.res.AssetManager;

import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class AssetResourceLoader implements ResourceLoader {

    private final AssetManager assets;

    public AssetResourceLoader(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public byte[] load(String source) {
        if (source == null) return null;
        String name = source.startsWith("asset:") ? source.substring("asset:".length()) : source;
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
