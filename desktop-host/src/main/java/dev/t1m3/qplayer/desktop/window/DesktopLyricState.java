package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.lyric.LyricTimeline;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

/** qml4j context object owned and mutated only by the desktop-lyric thread. */
public final class DesktopLyricState extends QObject {

    public final Property<String> currentText = new Property<>("");
    public final Property<String> translationText = new Property<>("");
    public final Property<String> nextText = new Property<>("");
    public final Property<Integer> fontSize = new Property<>(26);
    public final Property<Integer> fontWeight = new Property<>(2);
    public final Property<Boolean> shadow = new Property<>(Boolean.TRUE);
    public final Property<Boolean> dark = new Property<>(Boolean.TRUE);
    private float progress;
    private boolean darkValue = true;

    void update(DesktopLyricSnapshot snapshot, long nowNanos) {
        LyricTimeline.Frame frame = LyricTimeline.frameAt(
                snapshot.timeline, snapshot.predictedPosition(nowNanos));
        String current = frame.current;
        if (current.isEmpty()) current = fallback(snapshot);
        currentText.set(current);
        translationText.set(frame.translation);
        nextText.set(frame.next);
        progress = Math.max(0f, Math.min(1f, frame.progress));
        fontSize.set(Math.max(18, Math.min(38, snapshot.fontSize)));
        fontWeight.set(Math.max(0, Math.min(3, snapshot.fontWeight)));
        shadow.set(snapshot.shadow);
        darkValue = snapshot.dark;
        dark.set(snapshot.dark);
    }

    float progress() {
        return progress;
    }

    boolean darkValue() {
        return darkValue;
    }

    private static String fallback(DesktopLyricSnapshot snapshot) {
        if (snapshot.title.isEmpty()) return "";
        return snapshot.artist.isEmpty() ? snapshot.title : snapshot.title + "  ·  " + snapshot.artist;
    }
}
