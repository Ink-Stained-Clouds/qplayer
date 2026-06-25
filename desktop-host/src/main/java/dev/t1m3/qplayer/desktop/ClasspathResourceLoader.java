package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Resolves QML, fonts and assets off the classpath. The shared-qml tree
 * (Main.qml, the app pages, md3/Core/*, fonts/*) is mounted as a Maven resource
 * directory, so {@code load("Main.qml")} reads {@code /Main.qml} and
 * {@code import md3.Core} resolves {@code /md3/Core/X.qml} — the same layout the
 * Android shell sees through its merged assets.
 */
public final class ClasspathResourceLoader implements ResourceLoader {

    @Override
    public byte[] load(String source) {
        if (source == null) return null;
        String path = source.startsWith("/") ? source : "/" + source;
        try (InputStream in = ClasspathResourceLoader.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
