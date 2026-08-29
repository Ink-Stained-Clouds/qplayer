package dev.t1m3.qplayer.desktop.resources;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DiskDecompressedResourceCacheTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reusesExpandedPayloadAndRecoversFromCorruption() throws Exception {
        byte[] original = new byte[128 * 1024];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (i * 31);
        byte[] compressed = xz(original);
        AtomicInteger compressedReads = new AtomicInteger();
        var loader = (io.github.timer_err.qml4j.render.ResourceLoader) source -> {
            if (!"fonts/Test.otf.xz".equals(source)) return null;
            compressedReads.incrementAndGet();
            return compressed;
        };
        Path directory = temporaryFolder.getRoot().toPath();
        DiskDecompressedResourceCache cache =
                new DiskDecompressedResourceCache(loader, directory);

        assertArrayEquals(original, cache.load("fonts/Test.otf"));
        assertArrayEquals(original, cache.load("fonts/Test.otf"));
        assertEquals(1, compressedReads.get());

        Path entry;
        try (var files = Files.list(directory)) {
            entry = files.findFirst().orElseThrow();
        }
        byte[] corrupt = Files.readAllBytes(entry);
        Arrays.fill(corrupt, corrupt.length - 32, corrupt.length, (byte) 0);
        Files.write(entry, corrupt);

        DiskDecompressedResourceCache restarted =
                new DiskDecompressedResourceCache(loader, directory);
        assertArrayEquals(original, restarted.load("fonts/Test.otf"));
        assertEquals(2, compressedReads.get());
    }

    private static byte[] xz(byte[] input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZOutputStream output = new XZOutputStream(bytes, new LZMA2Options())) {
            output.write(input);
        }
        return bytes.toByteArray();
    }
}
