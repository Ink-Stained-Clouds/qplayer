package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.items.core.Canvas;
import io.github.timer_err.qml4j.render.items.core.Item;

/**
 * Invalidates the QML scene's GPU-backed caches after the {@code DirectContext}
 * they were created against has been destroyed (minimize-to-tray tears the GPU
 * stack down; restore builds a fresh context). The only GPU-bound cache in the
 * qml4j scene tree is {@link Canvas}'s offscreen {@code backing} surface — every
 * other item paints straight to the frame canvas — so a recursive walk that
 * closes + nulls each {@code Canvas.backing} is enough; they lazily rebuild
 * against the new context on the next paint.
 *
 * <p>Runs on the render thread, before the first frame of a respawned thread,
 * while no context is bound to the stale surfaces.
 */
final class GpuCaches {

    private GpuCaches() {}

    static void invalidate(Item root) {
        if (root == null) return;
        if (root instanceof Canvas) {
            Canvas c = (Canvas) root;
            if (c.backing != null) {
                try {
                    c.backing.close();
                } catch (Throwable ignored) {
                    // The surface is bound to a destroyed context; closing the Java
                    // handle is best-effort. Null it regardless so it rebuilds.
                }
                c.backing = null;
                c.backingW = 0;
                c.backingH = 0;
                c.dirty = true;
                c.requestPaint();
            }
        }
        // children is a plain List on every Item; recurse depth-first.
        for (int i = 0; i < root.children.size(); i++) {
            invalidate(root.children.get(i));
        }
    }
}
