package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * System-tray integration using java.awt.TrayIcon directly.
 *
 * <p>We previously used dorkbox SystemTray but on Windows 11 its
 * _WindowsNativeTray (Win32 + Swing JPopupMenu) silently drops all click
 * events, making the tray icon unresponsive. java.awt.TrayIcon + PopupMenu
 * is the lowest-level supported path on every platform and has no such issues.
 *
 * <p>Threading: AWT delivers PopupMenu action events on the AWT EDT. Every
 * callback funnels through win.postMainTask so GLFW + QML state is only
 * ever touched on the main event loop.
 */
final class TrayController implements PlayerController.PlaybackListener {

    private final PlayerController controller;
    private final DesktopWindow win;
    private final byte[] iconPng;

    private TrayIcon trayIcon;
    private MenuItem playPauseItem;

    TrayController(PlayerController controller, DesktopWindow win, byte[] iconPng) {
        this.controller = controller;
        this.win = win;
        this.iconPng = iconPng;
    }

    /** Build the tray. Returns false (and logs) if no tray is available, in which
     *  case the app still runs windowed. */
    boolean install() {
        if (!SystemTray.isSupported()) {
            Logger.warn("java.awt.SystemTray not supported; tray menu disabled");
            return false;
        }
        try {
            Image icon = loadIcon();

            PopupMenu popup = new PopupMenu();

            MenuItem prev = new MenuItem("上一首");
            prev.addActionListener(e -> win.postMainTask(controller::prev));
            popup.add(prev);

            playPauseItem = new MenuItem("播放 / 暂停");
            playPauseItem.addActionListener(e -> win.postMainTask(controller::toggle));
            popup.add(playPauseItem);

            MenuItem next = new MenuItem("下一首");
            next.addActionListener(e -> win.postMainTask(controller::next));
            popup.add(next);

            popup.addSeparator();

            MenuItem show = new MenuItem("显示窗口");
            show.addActionListener(e -> win.postMainTask(win::restoreFromTray));
            popup.add(show);

            MenuItem quit = new MenuItem("退出");
            quit.addActionListener(e -> win.postMainTask(() -> {
                shutdown();
                win.requestQuit();
            }));
            popup.add(quit);

            trayIcon = new TrayIcon(icon, "QPlayer", popup);
            trayIcon.setImageAutoSize(true);
            // Left-click also shows the popup (Windows default is right-click only).
            trayIcon.addActionListener(e -> win.postMainTask(win::restoreFromTray));

            SystemTray.getSystemTray().add(trayIcon);
            Logger.info("system tray initialized (java.awt.TrayIcon)");
            return true;
        } catch (Throwable t) {
            Logger.warn("system tray init failed: {}", t);
            return false;
        }
    }

    @Override
    public void onPlaybackChanged() {
        // May arrive on the audio/worker thread — marshal to the main loop.
        win.postMainTask(this::refresh);
    }

    private void refresh() {
        if (trayIcon == null) return;
        try {
            if (playPauseItem != null) {
                playPauseItem.setLabel(controller.isPlaying() ? "暂停" : "播放");
            }
            Object title = controller.title.peek();
            Object artist = controller.artist.peek();
            String tip = title == null ? "QPlayer"
                    : (artist != null ? artist + " — " + title : String.valueOf(title));
            // AWT caps tooltips at 64 chars on some platforms.
            if (tip.length() > 64) tip = tip.substring(0, 63) + "…";
            trayIcon.setToolTip(tip);
        } catch (Throwable t) {
            Logger.warn("tray refresh failed: {}", t);
        }
    }

    void shutdown() {
        if (trayIcon != null) {
            try { SystemTray.getSystemTray().remove(trayIcon); } catch (Throwable ignored) {}
            trayIcon = null;
        }
    }

    private Image loadIcon() {
        if (iconPng != null) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(iconPng));
                if (img != null) return img;
            } catch (Exception ignored) {}
        }
        return placeholder();
    }

    private static BufferedImage placeholder() {
        int n = 64;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x6750A4)); // MD3 primary-ish
        g.fillRoundRect(2, 2, n - 4, n - 4, 18, 18);
        g.setColor(Color.WHITE);
        int[] xs = {24, 24, 46};
        int[] ys = {18, 46, 32};
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return img;
    }
}
