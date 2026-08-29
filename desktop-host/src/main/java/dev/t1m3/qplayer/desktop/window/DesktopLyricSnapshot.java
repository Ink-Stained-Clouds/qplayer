package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.lyric.LyricTimeline;

/** Immutable hand-off from the main QML/controller thread to desktop lyrics. */
final class DesktopLyricSnapshot {

    static final DesktopLyricSnapshot EMPTY = new DesktopLyricSnapshot(
            null, "", "", 0L, false, System.nanoTime(), 0L,
            26, 2, true, true);

    final LyricTimeline.Prepared timeline;
    final String title;
    final String artist;
    final long positionMs;
    final boolean running;
    final long capturedNanos;
    final long offsetMs;
    final int fontSize;
    final int fontWeight;
    final boolean shadow;
    final boolean dark;

    DesktopLyricSnapshot(LyricTimeline.Prepared timeline, String title, String artist,
                         long positionMs, boolean running, long capturedNanos, long offsetMs,
                         int fontSize, int fontWeight, boolean shadow, boolean dark) {
        this.timeline = timeline;
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.positionMs = Math.max(0L, positionMs);
        this.running = running;
        this.capturedNanos = capturedNanos;
        this.offsetMs = offsetMs;
        this.fontSize = fontSize;
        this.fontWeight = fontWeight;
        this.shadow = shadow;
        this.dark = dark;
    }

    long predictedPosition(long nowNanos) {
        long predicted = positionMs;
        if (running && nowNanos > capturedNanos) predicted += (nowNanos - capturedNanos) / 1_000_000L;
        return Math.max(0L, predicted - offsetMs);
    }
}
