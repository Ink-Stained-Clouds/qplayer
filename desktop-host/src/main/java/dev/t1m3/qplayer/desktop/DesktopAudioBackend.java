package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.util.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AudioBackend} over {@code javax.sound.sampled}. A single decoder
 * thread owns the {@link SourceDataLine}; control methods mutate volatile
 * fields the loop polls. Local files and netease HTTP urls both flow through
 * {@code AudioSystem.getAudioInputStream}; the mp3spi / vorbisspi / jflac SPI
 * jars on the classpath extend coverage to mp3/ogg/flac transparently.
 *
 * <p>Adapted from Haedus's {@code ImplMusicAPI}, narrowed to "play one source
 * + fire onComplete" — playlist advancement lives in the controller now.
 */
public final class DesktopAudioBackend implements AudioBackend {

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** -1 = no seek pending; ≥0 = jump to that ms on next loop re-entry. */
    private final AtomicLong seekTargetMs = new AtomicLong(-1L);
    /** Added to {@code line.getMicrosecondPosition()} so position() reads as song time. */
    private volatile long seekBaseMs = 0L;

    private volatile String source;
    private volatile long sourceDurationMs = 0L;
    private volatile float volume = 0.8f;
    private volatile Thread audioThread;
    private volatile SourceDataLine line;
    private final Object lineLock = new Object();
    private volatile Runnable onComplete;

    @Override
    public void play(String src, long startMs) {
        if (src == null || src.isEmpty()) return;
        synchronized (lineLock) {
            this.source = src;
            seekTargetMs.set(Math.max(0L, startMs));
            playing.set(true);
            ensureAudioThread();
        }
    }

    @Override
    public void pause() {
        playing.set(false);
    }

    @Override
    public void resume() {
        if (source != null) playing.set(true);
    }

    @Override
    public boolean isPlaying() {
        return playing.get();
    }

    @Override
    public void seek(long ms) {
        seekTargetMs.set(Math.max(0L, ms));
    }

    @Override
    public long position() {
        SourceDataLine l = line;
        if (l == null) return 0L;
        return seekBaseMs + l.getMicrosecondPosition() / 1000L;
    }

    @Override
    public long duration() {
        return sourceDurationMs;
    }

    @Override
    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyVolume();
    }

    @Override
    public void setOnComplete(Runnable callback) {
        this.onComplete = callback;
    }

    @Override
    public void release() {
        shuttingDown.set(true);
        playing.set(false);
        Thread t = audioThread;
        if (t != null) t.interrupt();
        synchronized (lineLock) {
            closeLineLocked();
        }
    }

    private void applyVolume() {
        SourceDataLine l = line;
        if (l == null) return;
        try {
            FloatControl gain = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
            float db = volume <= 0f ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
        } catch (IllegalArgumentException ignored) {
            // MASTER_GAIN unsupported on this line — silent fallback.
        }
    }

    private void ensureAudioThread() {
        if (audioThread != null && audioThread.isAlive()) return;
        Thread t = new Thread(this::audioLoop, "qplayer-audio");
        t.setDaemon(true);
        audioThread = t;
        t.start();
    }

    private void audioLoop() {
        // The codec SPIs (mp3spi / vorbisspi / jflac) are resolved via
        // ServiceLoader on the thread context CL — pin it to ours so the
        // registry finds them regardless of how the host launched us.
        Thread.currentThread().setContextClassLoader(DesktopAudioBackend.class.getClassLoader());
        while (!shuttingDown.get()) {
            boolean reachedEnd = false;
            try {
                reachedEnd = playCurrentSource();
            } catch (Throwable e) {
                Logger.exception(e);
                playing.set(false);
            }
            if (reachedEnd) {
                // Source played through (not a pending seek / stop). Notify and idle.
                playing.set(false);
                Runnable cb = onComplete;
                if (cb != null) cb.run();
            }
            if (!playing.get() && seekTargetMs.get() < 0L) {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** @return true iff the source decoded all the way to EOF (natural end). */
    private boolean playCurrentSource() throws Exception {
        String src = source;
        if (src == null) {
            playing.set(false);
            return false;
        }
        long seekMs = Math.max(0L, seekTargetMs.getAndSet(-1L));
        boolean isUrl = src.startsWith("http://") || src.startsWith("https://");
        try (AudioInputStream raw = isUrl
                ? AudioSystem.getAudioInputStream(new URL(src))
                : AudioSystem.getAudioInputStream(new File(src))) {
            AudioFormat baseFmt = raw.getFormat();
            AudioFormat pcm = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFmt.getSampleRate(),
                    16,
                    baseFmt.getChannels(),
                    baseFmt.getChannels() * 2,
                    baseFmt.getSampleRate(),
                    false);
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcm, raw)) {
                long bytesPerSec = (long) pcm.getSampleRate() * pcm.getChannels() * 2L;
                // Seek by read-and-discard. We never call skip(): mp3spi/jlafc
                // either return 0 or corrupt the decoder's sub-band state, so
                // subsequent frames decode to noise. A full read keeps the
                // decoder position in sync with the PCM stream.
                if (seekMs > 0L) {
                    long skipBytes = alignToFrame(seekMs * bytesPerSec / 1000L, pcm.getFrameSize());
                    long remaining = skipBytes;
                    byte[] discard = new byte[16 * 1024];
                    while (remaining > 0L && !shuttingDown.get()) {
                        int toRead = (int) Math.min(discard.length, remaining);
                        int read = pcmStream.read(discard, 0, toRead);
                        if (read < 0) break;
                        remaining -= read;
                    }
                    long actual = skipBytes - remaining;
                    seekBaseMs = bytesPerSec > 0L ? actual * 1000L / bytesPerSec : seekMs;
                } else {
                    seekBaseMs = 0L;
                }
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcm);
                synchronized (lineLock) {
                    closeLineLocked();
                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(pcm);
                    line.start();
                    applyVolume();
                }
                return streamSamples(pcmStream);
            }
        } finally {
            synchronized (lineLock) {
                closeLineLocked();
            }
        }
    }

    private static long alignToFrame(long bytes, int frameSize) {
        int fs = Math.max(1, frameSize);
        return (bytes / fs) * fs;
    }

    /** @return true on natural EOF; false if interrupted by seek/pause/stop. */
    private boolean streamSamples(AudioInputStream pcmStream) throws IOException {
        byte[] buf = new byte[4096];
        while (!shuttingDown.get()) {
            // A pending seek means re-enter playCurrentSource with a fresh stream.
            if (seekTargetMs.get() >= 0L) return false;
            if (!playing.get()) {
                // Paused: hold the line open and wait.
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException e) {
                    return false;
                }
                continue;
            }
            int n = pcmStream.read(buf);
            if (n < 0) return true; // natural end of source
            int off = 0;
            while (off < n && playing.get() && !shuttingDown.get()) {
                int written = line.write(buf, off, n - off);
                if (written <= 0) break;
                off += written;
            }
        }
        return false;
    }

    private void closeLineLocked() {
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.drain();
            } catch (Throwable ignored) {
            }
            try {
                l.stop();
            } catch (Throwable ignored) {
            }
            try {
                l.close();
            } catch (Throwable ignored) {
            }
        }
        line = null;
    }
}
