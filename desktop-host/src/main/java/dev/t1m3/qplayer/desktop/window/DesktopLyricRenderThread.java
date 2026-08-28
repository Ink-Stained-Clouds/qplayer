package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.util.Logger;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.Renderer;

import java.util.concurrent.locks.LockSupport;

/** Owns the floating lyric window's backend, qml4j scene, and frame clock. */
final class DesktopLyricRenderThread extends Thread {

    private static final long FRAME_NANOS = 1_000_000_000L / 60L;

    private final DesktopLyricWindow owner;
    private final GraphicsBackend backend;
    private volatile boolean running = true;

    DesktopLyricRenderThread(DesktopLyricWindow owner) {
        super("qplayer-desktop-lyric-render");
        this.owner = owner;
        this.backend = new GLBackend(owner.window(), true);
    }

    void shutdown() {
        running = false;
        LockSupport.unpark(this);
    }

    @Override
    public void run() {
        QmlView view = null;
        Paint progressPaint = null;
        boolean firstFramePresented = false;
        try {
            DesktopLyricWindow.FramebufferSize size = owner.framebufferSize();
            backend.init(size.width(), size.height());
            DesktopLyricState state = new DesktopLyricState();
            synchronized (QmlRuntimeLock.MONITOR) {
                view = QmlView.withStockTypes(new QmlEngine()).resources(owner.resources());
                // QmlView defaults picture caching to false. In qml4j 0.2.x the
                // corresponding Item.contentCacheEnabled switch is unfortunately
                // static, so constructing this second, otherwise independent view
                // disables invalidation for the main view's cached cover/buttons.
                // Keep both instances on the same enabled mode until qml4j makes
                // that switch renderer-local.
                view.renderer().setPictureCache(true);
                DesktopWindow.loadFonts(view, owner.resources());
                view.context("desktopLyric", state);
                view.load(owner.qmlSource());
                if (view.root() != null) {
                    view.root().width.set((double) DesktopLyricWindow.WIDTH);
                    view.root().height.set((double) DesktopLyricWindow.HEIGHT);
                }
            }
            progressPaint = new Paint().setAntiAlias(true);
            Logger.info("desktop lyric render thread ready (backend {})", backend.kind());

            while (running) {
                if (!owner.isEnabled()) {
                    LockSupport.parkNanos(250_000_000L);
                    continue;
                }
                long frameStart = System.nanoTime();
                DirtyQueue dirty = view.dirtyQueue();
                synchronized (QmlRuntimeLock.MONITOR) {
                    dirty.install();
                    try {
                        state.update(owner.snapshot(), frameStart);
                        view.tickAnimations(frameStart);
                        dirty.flush();
                        size = owner.framebufferSize();
                        backend.resize(size.width(), size.height());
                        Canvas canvas = backend.acquireCanvas();
                        Renderer renderer = view.renderer();
                        renderer.setGpuContext(backend.recordingContext());
                        int save = canvas.save();
                        try {
                            canvas.scale(
                                    (float) size.width() / DesktopLyricWindow.WIDTH,
                                    (float) size.height() / DesktopLyricWindow.HEIGHT);
                            renderer.render(canvas, view.root(), false);
                            drawProgress(canvas, progressPaint, state);
                        } finally {
                            canvas.restoreToCount(save);
                        }
                    } finally {
                        dirty.uninstall();
                    }
                }
                backend.present();
                if (!firstFramePresented) {
                    firstFramePresented = true;
                    owner.onFirstFrameRendered();
                }
                long remaining = FRAME_NANOS - (System.nanoTime() - frameStart);
                if (remaining > 0L) LockSupport.parkNanos(remaining);
            }
        } catch (Throwable error) {
            if (running) owner.onRenderError(error);
        } finally {
            synchronized (QmlRuntimeLock.MONITOR) {
                try {
                    if (view != null) GpuCaches.invalidate(view.root());
                } catch (Throwable ignored) {
                }
                try {
                    if (view != null) view.dispose();
                } catch (Throwable ignored) {
                }
            }
            if (progressPaint != null) progressPaint.close();
            try {
                backend.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void drawProgress(Canvas canvas, Paint paint, DesktopLyricState state) {
        float width = DesktopLyricWindow.WIDTH * state.progress();
        if (width <= 0f) return;
        paint.setColor(state.darkValue() ? 0x99FFFFFF : 0x9938383C);
        canvas.drawRect(Rect.makeXYWH(0f, DesktopLyricWindow.HEIGHT - 3f, width, 3f), paint);
    }
}
