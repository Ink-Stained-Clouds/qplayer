package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Unified disk cache for audio files, lyrics and cover images.
 * <p>
 * Four sub-directories under {@code AppDirs.cacheBase()/cache/}:
 * {@code audio/}, {@code lyric/}, {@code image/}, {@code thumb64/}.
 * <p>
 * LRU eviction by last-modified time: after every write the total size is
 * checked against {@link #maxSizeBytes} and oldest files are deleted until
 * the limit is satisfied.  Callers should use the typed helper methods
 * ({@link #cacheAudio}, {@link #cacheLyric}, {@link #cacheImage}) which
 * touch the file on read (via {@link #getAudio}, etc.) so that actively-used
 * entries survive eviction.
 */
public final class DiskCache {

    /** Not final: {@link #setBaseDir} lets the desktop settings page repoint the
     *  cache root at runtime, which a compile-time constant couldn't support. */
    private volatile String baseDir = AppDirs.cacheDir().toString();

    /** Sub-directory names. */
    public static final String AUDIO   = "audio";
    public static final String LYRIC   = "lyric";
    public static final String IMAGE   = "image";
    /** Offline-playlist-browsing thumbnails (64x64), kept separate from the
     *  general {@link #IMAGE} cache: capped by file *count*
     *  ({@link #THUMB64_MAX_COUNT}), not the byte-size budget the other three
     *  sub-caches share, since a meaningful byte budget for images this tiny
     *  would be a near-unlimited file count anyway. */
    public static final String THUMB64 = "thumb64";

    /** Oldest files (by lastModified) are deleted once the count exceeds this,
     *  every time a new one is cached — see {@link #cacheThumb64}. */
    private static final int THUMB64_MAX_COUNT = 128;

    private volatile long maxSizeBytes;

    public DiskCache(long maxSizeMB) {
        this.maxSizeBytes = maxSizeMB * 1024L * 1024L;
    }

    public void setMaxSizeMB(long mb) {
        this.maxSizeBytes = mb * 1024L * 1024L;
        evictIfNeeded();
    }

    public long getMaxSizeMB() {
        return maxSizeBytes / (1024L * 1024L);
    }

    /** Repoint the cache root (e.g. the desktop "custom cache location" setting).
     *  Does not move existing files — the caller decides whether to migrate or
     *  just let the old location go stale. */
    public void setBaseDir(String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        this.baseDir = Paths.get(dir, "cache").toString();
    }

    public String baseDir() {
        return baseDir;
    }

    // ---- path helpers ----------------------------------------------------

    /** Resolve cache file for an audio track keyed by netease song id. */
    public String audioPath(long neteaseId) {
        if (neteaseId <= 0) return null;
        return baseDir + "/" + AUDIO + "/" + neteaseId + ".cache";
    }

    /** Resolve cache file for AMLL TTML lyrics keyed by song id. */
    public String lyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".ttml";
    }

    /** Resolve cache file for Netease's own lyric payload (serialized YRC/LRC). */
    public String neteaseLyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".nlrc";
    }

    /** Resolve cache file for a cover image keyed by url hash. */
    public String imagePath(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + IMAGE + "/" + Math.abs(url.hashCode()) + ".img";
    }

    /** Resolve cache file for a 64x64 offline-playlist thumbnail, keyed by
     *  url hash (the url is expected to already carry its own size param,
     *  e.g. {@code ?param=64y64} — same convention as {@link #imagePath}). */
    public String thumb64Path(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + THUMB64 + "/" + Math.abs(url.hashCode()) + ".img";
    }

    // ---- existence check -------------------------------------------------

    public boolean hasAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return p != null && new File(p).exists();
    }

    /** Delete a single cached audio file (cached-songs list right-click menu). */
    public boolean deleteAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        if (p == null) return false;
        File f = new File(p);
        return f.exists() && f.delete();
    }

    public boolean hasLyric(long songId) {
        String p = lyricPath(songId);
        return p != null && new File(p).exists();
    }

    public boolean hasImage(String url) {
        String p = imagePath(url);
        return p != null && new File(p).exists();
    }

    public boolean hasThumb64(String url) {
        String p = thumb64Path(url);
        return p != null && new File(p).exists();
    }

    // ---- read (touches lastModified for LRU) ------------------------------

    /**
     * Return the cached audio file path, touching its timestamp so it
     * survives LRU eviction. Returns null if not cached.
     */
    public String getAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return touch(p);
    }

    /** Return the cached AMLL TTML lyric file path, or null. */
    public String getLyric(long songId) {
        String p = lyricPath(songId);
        return touch(p);
    }

    /** Return the cached Netease lyric payload file path, or null. */
    public String getNeteaseLyric(long songId) {
        String p = neteaseLyricPath(songId);
        return touch(p);
    }

    /** Return the cached image file path, or null. */
    public String getImage(String url) {
        String p = imagePath(url);
        return touch(p);
    }

    /** Return the cached 64x64 thumbnail file path, or null. */
    public String getThumb64(String url) {
        String p = thumb64Path(url);
        return touch(p);
    }

    // ---- write (download to cache) ---------------------------------------

    /**
     * Download an HTTP URL straight to the audio cache file.
     * Non-fatal: logs and cleans up on error.
     */
    public void cacheAudio(String url, long neteaseId) {
        String path = audioPath(neteaseId);
        downloadToFile(url, path);
    }

    /** Write raw bytes to the AMLL TTML lyric cache file. */
    public void cacheLyric(byte[] data, long songId) {
        String path = lyricPath(songId);
        writeBytes(data, path);
    }

    /** Write the serialized Netease lyric payload to its cache file. */
    public void cacheNeteaseLyric(byte[] data, long songId) {
        String path = neteaseLyricPath(songId);
        writeBytes(data, path);
    }

    /** Download an HTTP URL to the image cache file. */
    public void cacheImage(String url) {
        String path = imagePath(url);
        downloadToFile(url, path);
    }

    /** Download an HTTP URL to the 64x64 thumbnail cache file, then evict the
     *  oldest thumbnails (by lastModified) past {@link #THUMB64_MAX_COUNT} —
     *  a file *count* cap, independent of the byte-size budget the other
     *  three sub-caches share via {@link #evictIfNeeded}. */
    public void cacheThumb64(String url) {
        String path = thumb64Path(url);
        downloadToFile(url, path, true);
        evictThumb64IfOverCount();
    }

    // ---- size & cleanup ---------------------------------------------------

    /** Total bytes used by all four cache sub-directories. */
    public long totalSize() {
        long total = 0;
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64}) {
            total += dirSize(new File(baseDir, sub));
        }
        return total;
    }

    /** Delete all cached files. */
    public void clearAll() {
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64}) {
            deleteRecursive(new File(baseDir, sub));
        }
    }

    /** Delete all cached files of one type. */
    public void clearType(String type) {
        deleteRecursive(new File(baseDir, type));
    }

    // ---- internals --------------------------------------------------------

    private String touch(String path) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        f.setLastModified(System.currentTimeMillis());
        return path;
    }

    private void writeBytes(byte[] data, String path) {
        if (data == null || path == null) return;
        try {
            StorageFiles.writeBytesAtomic(Paths.get(path), data);
            Logger.info("disk cache written: {} ({} B)", fileName(path), data.length);
        } catch (Throwable e) {
            Logger.warn("disk cache write failed: {}", e.getMessage());
        }
        evictIfNeeded();
    }

    private void downloadToFile(String url, String path) {
        downloadToFile(url, path, false);
    }

    /** {@code quiet} suppresses the per-file success line: warming a playlist's
     *  thumbnails queues one download per track, which drowned the log (a few
     *  hundred lines per playlist opened). Failures are still logged. */
    private void downloadToFile(String url, String path, boolean quiet) {
        if (url == null || path == null) return;
        ensureParent(path);
        HttpURLConnection c = null;
        Path target = Paths.get(path);
        Path pending = StorageFiles.pendingPath(target);
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "qplayer/1.0");
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(pending.toFile())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            StorageFiles.replace(pending, target);
            if (!quiet) {
                Logger.info("disk cache downloaded: {} ({} B)", fileName(path), new File(path).length());
            }
        } catch (Throwable e) {
            Logger.warn("disk cache download failed: {}", e.getMessage());
            try { Files.deleteIfExists(pending); } catch (Throwable ignored) {}
        } finally {
            if (c != null) c.disconnect();
        }
        evictIfNeeded();
    }

    /**
     * If total cache size exceeds {@link #maxSizeBytes}, delete the
     * least-recently-used files (oldest lastModified) until under limit.
     */
    private void evictIfNeeded() {
        long limit = maxSizeBytes;
        if (limit <= 0) return; // 0 = unlimited
        long total = totalSize();
        if (total <= limit) return;

        // Collect all cache files across all sub-dirs.
        File[] dirs = {new File(baseDir, AUDIO), new File(baseDir, LYRIC), new File(baseDir, IMAGE)};
        java.util.List<File> files = new java.util.ArrayList<>();
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                File[] children = dir.listFiles();
                if (children != null) files.addAll(Arrays.asList(children));
            }
        }
        // Sort by lastModified ascending (oldest first).
        files.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        for (File f : files) {
            if (total <= limit) break;
            long sz = f.length();
            if (f.delete()) {
                total -= sz;
                Logger.info("disk cache evicted: {}", f.getName());
            }
        }
    }

    /** Count (not byte-size) cap on {@link #THUMB64}: delete the oldest files
     *  once there are more than {@link #THUMB64_MAX_COUNT}. */
    private void evictThumb64IfOverCount() {
        File dir = new File(baseDir, THUMB64);
        File[] files = dir.listFiles();
        if (files == null || files.length <= THUMB64_MAX_COUNT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int overBy = files.length - THUMB64_MAX_COUNT;
        // Unlogged: this runs after every warmed thumbnail, so once the cache is at
        // its cap it fires on each one -- a line here is pure noise, not a signal.
        for (int i = 0; i < overBy; i++) {
            files[i].delete();
        }
    }

    private static void ensureParent(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    private static long dirSize(File dir) {
        if (!dir.isDirectory()) return 0;
        long total = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) total += f.length();
        }
        return total;
    }

    private static void deleteRecursive(File dir) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) f.delete();
        }
        dir.delete();
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
