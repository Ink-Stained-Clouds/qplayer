package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * System-tray integration: java.awt.TrayIcon for the icon/notification, plus a
 * Swing JPopupMenu for the right-click menu.
 *
 * <p>Why Swing popup instead of the native java.awt.PopupMenu: the native Win32
 * menu is rendered with Segoe UI (the system menu font), which carries no CJK
 * glyphs, so every Chinese label appears as □. Swing's JPopupMenu uses Java2D
 * with proper font fallback to Microsoft YaHei / SimSun, so Chinese text renders
 * correctly. A hidden JFrame acts as the invoker so the popup gains focus and
 * dismisses properly.
 *
 * <p>Threading: MouseListener fires on the AWT EDT. Every callback funnels
 * through win.postMainTask so GLFW + QML state is only touched on the main loop.
 */
final class TrayController implements PlayerController.PlaybackListener {

    private final PlayerController controller;
    private final DesktopWindow win;
    private final byte[] iconPng;

    private TrayIcon trayIcon;
    private JPopupMenu popup;
    private JFrame invokerFrame;
    private JMenuItem playPauseItem;

    TrayController(PlayerController controller, DesktopWindow win, byte[] iconPng) {
        this.controller = controller;
        this.win = win;
        this.iconPng = iconPng;
    }

    boolean install() {
        if (!SystemTray.isSupported()) {
            Logger.warn("java.awt.SystemTray not supported; tray menu disabled");
            return false;
        }
        try {
            // Hidden frame: gives JPopupMenu a focusable parent so it can receive
            // keyboard events and auto-dismisses when focus is lost.
            invokerFrame = new JFrame();
            invokerFrame.setUndecorated(true);
            invokerFrame.setSize(1, 1);
            invokerFrame.setAlwaysOnTop(true);

            popup = new JPopupMenu();

            JMenuItem prev = new JMenuItem("上一首");
            prev.addActionListener(e -> win.postMainTask(controller::prev));
            popup.add(prev);

            playPauseItem = new JMenuItem("播放 / 暂停");
            playPauseItem.addActionListener(e -> win.postMainTask(controller::toggle));
            popup.add(playPauseItem);

            JMenuItem next = new JMenuItem("下一首");
            next.addActionListener(e -> win.postMainTask(controller::next));
            popup.add(next);

            popup.addSeparator();

            JMenuItem show = new JMenuItem("显示窗口");
            show.addActionListener(e -> win.postMainTask(win::restoreFromTray));
            popup.add(show);

            JMenuItem quit = new JMenuItem("退出");
            quit.addActionListener(e -> win.postMainTask(() -> {
                shutdown();
                win.requestQuit();
            }));
            popup.add(quit);

            // Hide the invoker frame once the popup closes.
            popup.addPopupMenuListener(new PopupMenuListener() {
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    if (invokerFrame != null) invokerFrame.setVisible(false);
                }
                public void popupMenuCanceled(PopupMenuEvent e) {
                    if (invokerFrame != null) invokerFrame.setVisible(false);
                }
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
            });

            trayIcon = new TrayIcon(loadIcon(), "QPlayer");
            // Do NOT setImageAutoSize: we provide an image pre-scaled to the
            // physical pixel size using bicubic resampling, which is sharper
            // than TrayIcon's built-in nearest-neighbour auto-scale.

            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) showPopup();
                }
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) showPopup();
                }
            });
            // Left-click: restore window.
            trayIcon.addActionListener(e -> win.postMainTask(win::restoreFromTray));

            SystemTray.getSystemTray().add(trayIcon);
            Logger.info("system tray initialized (java.awt.TrayIcon + JPopupMenu)");
            return true;
        } catch (Throwable t) {
            Logger.warn("system tray init failed: {}", t);
            return false;
        }
    }

    private void showPopup() {
        SwingUtilities.invokeLater(() -> {
            if (invokerFrame == null || popup == null) return;
            refresh();

            // TrayIcon MouseEvent coordinates are wrong on Windows (JDK bug);
            // use MouseInfo for the real cursor position (AWT logical coords).
            Point loc = MouseInfo.getPointerInfo().getLocation();
            Dimension ps = popup.getPreferredSize();

            // Use GraphicsConfiguration for DPI-correct screen/workarea bounds
            // (gc.getBounds() returns logical pixels, consistent with AWT window
            // positioning). Toolkit.getScreenSize() returns physical pixels on some
            // JVM/Windows combinations, causing Swing to miscalculate whether the
            // popup fits and skipping the edge-flip — which is why the menu was
            // stuck in the corner.
            GraphicsConfiguration gc = invokerFrame.getGraphicsConfiguration();
            if (gc == null) gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle screen = gc.getBounds();
            Insets ins = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            int left   = screen.x + ins.left;
            int top    = screen.y + ins.top;
            int right  = screen.x + screen.width  - ins.right;
            int bottom = screen.y + screen.height - ins.bottom;

            // Position popup at cursor, clamped to usable workarea.
            // When the tray is at the bottom-right, this naturally places the
            // menu above and to the left of the cursor.
            int x = Math.min(loc.x, right  - ps.width);
            int y = Math.min(loc.y, bottom - ps.height);
            x = Math.max(x, left);
            y = Math.max(y, top);

            invokerFrame.setLocation(x, y);
            invokerFrame.setVisible(true);
            popup.show(invokerFrame, 0, 0);
        });
    }

    @Override
    public void onPlaybackChanged() {
        win.postMainTask(this::refresh);
    }

    private void refresh() {
        if (trayIcon == null) return;
        try {
            if (playPauseItem != null) {
                playPauseItem.setText(controller.isPlaying() ? "暂停" : "播放");
            }
            Object title = controller.title.peek();
            Object artist = controller.artist.peek();
            String tip = title == null ? "QPlayer"
                    : (artist != null ? artist + " — " + title : String.valueOf(title));
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
        if (invokerFrame != null) {
            try { invokerFrame.dispose(); } catch (Throwable ignored) {}
            invokerFrame = null;
        }
    }

    private Image loadIcon() {
        BufferedImage src = null;
        if (iconPng != null) {
            try { src = ImageIO.read(new ByteArrayInputStream(iconPng)); } catch (Exception ignored) {}
        }
        if (src == null) src = placeholder();
        return scaledForTray(src);
    }

    /**
     * Scale {@code src} to the physical pixel size of the system-tray slot.
     *
     * <p>Why: Java's {@code TrayIcon.setImageAutoSize(true)} uses
     * {@code Image.SCALE_DEFAULT} (nearest-neighbour on most platforms), which
     * produces a blurry or blocky result when downsampling from a large source.
     * Here we drive the scale ourselves with {@code BICUBIC + RENDER_QUALITY},
     * and target the <em>physical</em> pixel size (logical tray size × DPI scale)
     * so the icon is sharp on HiDPI displays.
     */
    private static Image scaledForTray(BufferedImage src) {
        Dimension logicalSize = SystemTray.getSystemTray().getTrayIconSize();
        // DPI scale for the default screen (e.g. 1.25 at 125 % DPI).
        AffineTransform tx = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration()
                .getDefaultTransform();
        double scale = Math.max(tx.getScaleX(), tx.getScaleY());
        int w = Math.max(1, (int) Math.round(logicalSize.width  * scale));
        int h = Math.max(1, (int) Math.round(logicalSize.height * scale));

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage placeholder() {
        int n = 64;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x6750A4));
        g.fillRoundRect(2, 2, n - 4, n - 4, 18, 18);
        g.setColor(Color.WHITE);
        g.fillPolygon(new int[]{24, 24, 46}, new int[]{18, 46, 32}, 3);
        g.dispose();
        return img;
    }
}
