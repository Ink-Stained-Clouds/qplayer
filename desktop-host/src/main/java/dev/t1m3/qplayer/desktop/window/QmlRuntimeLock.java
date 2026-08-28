package dev.t1m3.qplayer.desktop.window;

/**
 * Serializes qml4j mutation/layout across the two desktop scenes.
 *
 * <p>Dirty queues are thread-local, but qml4j 0.2.x still keeps its property
 * change version and a few renderer switches in process-wide static fields.
 * The monitor supplies the missing cross-thread happens-before edge without
 * coupling either scene's GPU present or frame pacing to the other.
 */
final class QmlRuntimeLock {

    static final Object MONITOR = new Object();

    private QmlRuntimeLock() {
    }
}
