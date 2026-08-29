package dev.t1m3.qplayer.desktop.resources;

import io.github.timer_err.qml4j.compiler.CompiledScene;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Computes the content-addressed identity used by the desktop QML cache. */
final class QmlResourceFingerprint {

    private static final byte[] CACHE_SCHEMA =
            "qplayer-desktop-qml-cache-v1".getBytes(StandardCharsets.UTF_8);

    private QmlResourceFingerprint() {
    }

    static String create(String applicationVersion)
            throws IOException, URISyntaxException, NoSuchAlgorithmException {
        Map<String, byte[]> resources = readLocation(locationOf(ClasspathResourceLoader.class),
                QmlResourceFingerprint::isQmlResource);
        Map<String, byte[]> engineClasses = readLocation(locationOf(CompiledScene.class),
                name -> name.endsWith(".class"));
        MessageDigest engineDigest = MessageDigest.getInstance("SHA-256");
        updateEntries(engineDigest, engineClasses);
        return digest(applicationVersion, engineDigest.digest(), resources);
    }

    static String digest(String applicationVersion, byte[] engineFingerprint,
                         Map<String, byte[]> resources) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateBytes(digest, CACHE_SCHEMA);
        updateString(digest, applicationVersion == null ? "" : applicationVersion);
        updateString(digest, Integer.toString(CompiledScene.FORMAT_VERSION));
        updateBytes(digest, engineFingerprint);
        Map<String, byte[]> relevant = new LinkedHashMap<>();
        resources.entrySet().stream()
                .filter(entry -> isQmlResource(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> relevant.put(normalize(entry.getKey()), entry.getValue()));
        updateEntries(digest, relevant);
        return hex(digest.digest());
    }

    private static URL locationOf(Class<?> type) throws IOException {
        if (type.getProtectionDomain() == null
                || type.getProtectionDomain().getCodeSource() == null) {
            throw new IOException("code source unavailable for " + type.getName());
        }
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    private static Map<String, byte[]> readLocation(URL location, Predicate<String> include)
            throws IOException, URISyntaxException {
        Path path = Paths.get(location.toURI());
        return Files.isDirectory(path) ? readDirectory(path, include) : readJar(path, include);
    }

    private static Map<String, byte[]> readDirectory(Path root, Predicate<String> include)
            throws IOException {
        List<Path> paths = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> include.test(normalize(root.relativize(path).toString())))
                    .forEach(paths::add);
        }
        paths.sort(Comparator.comparing(path -> normalize(root.relativize(path).toString())));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Path path : paths) {
            entries.put(normalize(root.relativize(path).toString()), Files.readAllBytes(path));
        }
        return entries;
    }

    private static Map<String, byte[]> readJar(Path path, Predicate<String> include)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(path.toFile())) {
            List<JarEntry> selected = jar.stream()
                    .filter(entry -> !entry.isDirectory() && include.test(entry.getName()))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList();
            for (JarEntry entry : selected) {
                try (var input = jar.getInputStream(entry)) {
                    entries.put(normalize(entry.getName()), input.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static boolean isQmlResource(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return normalized.endsWith(".qml") || normalized.endsWith(".js")
                || "qmldir".equals(name);
    }

    private static void updateEntries(MessageDigest digest, Map<String, byte[]> entries) {
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            updateString(digest, normalize(entry.getKey()));
            updateBytes(digest, entry.getValue());
        });
    }

    private static void updateString(MessageDigest digest, String value) {
        updateBytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateBytes(MessageDigest digest, byte[] value) {
        int length = value.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(value);
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
