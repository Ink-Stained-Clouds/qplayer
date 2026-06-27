package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

/**
 * System-tray integration (the desktop analogue of the Android PlaybackService).
 *
 * <p>Two backends:
 * <ul>
 *   <li><b>Windows</b> ({@link WinTray}): Shell_NotifyIcon + a native Win32 popup
 *       menu via JNA. The menu is rendered by the OS, so CJK works with zero
 *       {@code java.awt} font-manager init — which dies in the native image
 *       ({@code sun.awt.FontConfiguration} NPEs with no JDK lib dir).
 *   <li><b>Linux / macOS</b>: AWT {@link TrayIcon} + a Swing {@link javax.swing.JPopupMenu}
 *       (Java2D-drawn, so setFont controls the CJK font). Works on the JVM; the
 *       native backends for these platforms are a follow-up.
 * </ul>
 *
 * <p>Threading: tray callbacks arrive on a backend thread (the Win32 pump thread
 * or AWT's EDT) and {@link PlayerController.PlaybackListener#onPlaybackChanged} may
 * fire from the audio/worker threads, so every action funnels through the window's
 * main-task queue — the same queue playback control runs on.
 */
final class TrayController implements PlayerController.PlaybackListener {

    private final PlayerController controller;
    private final DesktopWindow win;
    private final byte[] iconPng;

    // Windows backend (non-null when active).
    private WinTray winTray;
    private Object winPlayPause;

    // AWT backend (non-Windows).
    private TrayIcon trayIcon;
    private javax.swing.JPopupMenu popup;
    private javax.swing.JDialog popupAnchor;
    private javax.swing.JMenuItem playPause;
    private Font menuFont;

    TrayController(PlayerController controller, DesktopWindow win, byte[] iconPng) {
        this.controller = controller;
        this.win = win;
        this.iconPng = iconPng;
    }

    /** Build the tray. Returns false (and logs) if no tray is available, in which
     *  case the app still runs windowed. */
    boolean install() {
        return isWindows() ? installWin() : installAwt();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ---------- Windows backend ----------
    private boolean installWin() {
        try {
            winTray = new WinTray();
            winTray.setIconPng(iconPng != null ? iconPng : placeholderPng());
            winTray.addItem("上一首", () -> win.postMainTask(controller::prev));
            winPlayPause = winTray.addItem("播放 / 暂停", () -> win.postMainTask(controller::toggle));
            winTray.addItem("下一首", () -> win.postMainTask(controller::next));
            winTray.addSeparator();
            winTray.addItem("显示窗口", () -> win.postMainTask(win::restoreFromTray));
            winTray.addItem("退出", () -> win.postMainTask(() -> {
                shutdown();
                win.requestQuit();
            }));
            if (winTray.install()) return true;
            winTray = null;
            return false;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("Windows tray init failed:\n{}", sw);
            winTray = null;
            return false;
        }
    }

    // ---------- AWT backend (Linux / macOS) ----------
    private boolean installAwt() {
        if (!SystemTray.isSupported()) {
            Logger.warn("system tray not supported; tray menu disabled");
            return false;
        }
        try {
            File icon = iconFile();
            Image img = (icon != null) ? ImageIO.read(icon) : placeholder();
            menuFont = pickCjkFont();

            popup = new javax.swing.JPopupMenu();
            popup.add(swingItem("上一首", controller::prev));
            playPause = swingItem("播放 / 暂停", controller::toggle);
            popup.add(playPause);
            popup.add(swingItem("下一首", controller::next));
            popup.addSeparator();
            popup.add(swingItem("显示窗口", win::restoreFromTray));
            popup.add(swingItem("退出", () -> {
                shutdown();
                win.requestQuit();
            }));

            // JPopupMenu needs a parent component to anchor against; a tiny
            // undecorated always-on-top dialog placed at the click point doubles
            // as that anchor and as the focus owner (the popup auto-dismisses
            // when this anchor loses focus).
            popupAnchor = new javax.swing.JDialog();
            popupAnchor.setUndecorated(true);
            popupAnchor.setAlwaysOnTop(true);
            popupAnchor.setSize(1, 1);
            popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                    if (popupAnchor != null) popupAnchor.setVisible(false);
                }
            });

            trayIcon = new TrayIcon(img, "QPlayer");
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent e) { showPopupAt(e.getX(), e.getY()); }
            });
            SystemTray.getSystemTray().add(trayIcon);
            Logger.info("system tray initialized: AWT (font={})",
                    menuFont != null ? menuFont.getFamily() : "default");
            return true;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("system tray init failed:\n{}", sw);
            trayIcon = null;
            return false;
        }
    }

    @Override
    public void onPlaybackChanged() {
        // May arrive on the audio/worker thread — marshal to the main loop.
        win.postMainTask(this::refresh);
    }

    private void refresh() {
        try {
            String pp = controller.isPlaying() ? "暂停" : "播放";
            Object title = controller.title.peek();
            Object artist = controller.artist.peek();
            String tip = title == null ? "QPlayer"
                    : (artist != null ? artist + " — " + title : String.valueOf(title));
            // Tooltips: AWT caps at 127 chars on Windows, Win32 szTip at 128; trim well under.
            if (tip.length() > 64) tip = tip.substring(0, 63) + "…";

            if (winTray != null) {
                winTray.setLabel(winPlayPause, pp);
                winTray.setTooltip(tip);
            } else if (trayIcon != null) {
                String tipF = tip;
                if (playPause != null) {
                    javax.swing.SwingUtilities.invokeLater(() -> playPause.setText(pp));
                }
                trayIcon.setToolTip(tipF);
            }
        } catch (Throwable t) {
            Logger.warn("tray refresh failed: {}", t);
        }
    }

    void shutdown() {
        if (winTray != null) {
            try { winTray.shutdown(); } catch (Throwable ignored) {}
            winTray = null;
        }
        if (trayIcon != null) {
            try { SystemTray.getSystemTray().remove(trayIcon); } catch (Throwable ignored) {}
            trayIcon = null;
        }
        if (popupAnchor != null) {
            try { popupAnchor.dispose(); } catch (Throwable ignored) {}
            popupAnchor = null;
        }
    }

    private void showPopupAt(int screenX, int screenY) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (popup == null || popupAnchor == null) return;
            Dimension pref = popup.getPreferredSize();
            Rectangle screen = boundsContaining(screenX, screenY);
            // Snap the popup's bottom-right corner to the click point; clamping
            // keeps it on-screen for any panel position.
            int x = screenX - pref.width;
            int y = screenY - pref.height;
            if (x < screen.x) x = screen.x;
            if (y < screen.y) y = screen.y;
            if (x + pref.width > screen.x + screen.width) x = screen.x + screen.width - pref.width;
            if (y + pref.height > screen.y + screen.height) y = screen.y + screen.height - pref.height;
            popupAnchor.setLocation(x, y);
            popupAnchor.setVisible(true);
            popupAnchor.toFront();
            popup.show(popupAnchor.getContentPane(), 0, 0);
        });
    }

    private static Rectangle boundsContaining(int px, int py) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            if (b.contains(px, py)) return b;
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private javax.swing.JMenuItem swingItem(String label, Runnable action) {
        javax.swing.JMenuItem mi = new javax.swing.JMenuItem(label);
        if (menuFont != null) mi.setFont(menuFont);
        mi.addActionListener(e -> win.postMainTask(action));
        return mi;
    }

    /** Pin a CJK family; macOS default (Helvetica Neue) lacks CJK glyphs and falls
     *  through to the JDK's logical-font chain, which renders tofu on stripped /
     *  non-fontconfig JDKs. */
    private static Font pickCjkFont() {
        String[] candidates = {
                "PingFang SC", "Hiragino Sans GB",         // macOS
                "Noto Sans CJK SC", "WenQuanYi Micro Hei", // Linux
                "SimSun", "SimHei"
        };
        java.util.Set<String> available = new java.util.HashSet<>(
                java.util.Arrays.asList(
                        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : candidates) {
            if (available.contains(name)) return new Font(name, Font.PLAIN, 12);
        }
        return null;
    }

    private File iconFile() {
        try {
            File f = File.createTempFile("qplayer-tray", ".png");
            f.deleteOnExit();
            if (iconPng != null) {
                java.nio.file.Files.write(f.toPath(), iconPng);
            } else {
                ImageIO.write(placeholder(), "png", f);
            }
            return f;
        } catch (Exception e) {
            Logger.warn("tray icon temp write failed: {}", e);
            return null;
        }
    }

    /** Encode the generated placeholder to PNG bytes (icon resource is absent). */
    private static byte[] placeholderPng() {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            ImageIO.write(placeholder(), "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            Logger.warn("placeholder PNG encode failed: {}", e);
            return null;
        }
    }

    private static java.awt.image.BufferedImage placeholder() {
        int n = 64;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x6750A4));
        g.fillRoundRect(2, 2, n - 4, n - 4, 18, 18);
        g.setColor(Color.WHITE);
        int[] xs = {24, 24, 46};
        int[] ys = {18, 46, 32};
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return img;
    }
}
