package dev.t1m3.qplayer.desktop.app;

import ca.weblite.webview.swing.WebViewComponent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * In-process official-site login using the OS browser engine supplied by
 * swingwebview. The native cookie store is queried directly so HttpOnly
 * MUSIC_U is available; no second JVM, local HTTP callback, or credential log
 * is involved.
 */
final class DesktopWebLogin {
    private static final String LOGIN_URL = "https://music.163.com/#/login";
    private static final String COOKIE_URL = "https://music.163.com/";

    private static JFrame activeFrame;

    private DesktopWebLogin() {}

    static void open(Consumer<String> onCookie, Consumer<String> onFailure,
            Runnable onCancel) {
        SwingUtilities.invokeLater(() -> {
            if (activeFrame != null && activeFrame.isDisplayable()) {
                activeFrame.setVisible(true);
                activeFrame.toFront();
                activeFrame.requestFocus();
                return;
            }
            try {
                createWindow(onCookie, onFailure, onCancel);
            } catch (Throwable error) {
                activeFrame = null;
                onFailure.accept(friendlyError(error));
            }
        });
    }

    /** Close an in-flight login before the desktop host exits. JFrame disposal
     *  alone does not release swingwebview's native peer/WebKit child processes. */
    static void shutdown() {
        Runnable close = () -> {
            JFrame frame = activeFrame;
            if (frame != null && frame.isDisplayable()) frame.dispose();
            activeFrame = null;
        };
        if (SwingUtilities.isEventDispatchThread()) {
            close.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(close);
        } catch (Exception ignored) {
            // The AWT event thread may already be shutting down.
        }
    }

    private static void createWindow(Consumer<String> onCookie,
            Consumer<String> onFailure, Runnable onCancel) {
        WebViewComponent webView = WebViewComponent.create();
        webView.setUrl(LOGIN_URL);
        webView.setPreferredSize(new Dimension(900, 700));

        JFrame frame = new JFrame("登录网易云音乐");
        activeFrame = frame;
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(webView, BorderLayout.CENTER);
        frame.setMinimumSize(new Dimension(640, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);

        final boolean[] submitted = {false};
        final boolean[] queryInFlight = {false};
        final boolean[] disposed = {false};
        final int[] consecutiveFailures = {0};
        final long openedAt = System.currentTimeMillis();
        Timer cookiePoll = new Timer(650, event -> {
            if (queryInFlight[0] || !frame.isDisplayable()) return;
            queryInFlight[0] = true;
            webView.getCookies(COOKIE_URL).whenComplete((header, error) -> {
                queryInFlight[0] = false;
                if (!frame.isDisplayable() || submitted[0]) return;
                if (error != null) {
                    consecutiveFailures[0]++;
                    // Attach can still be pending during the first few ticks. Only
                    // surface a genuine persistent native-engine failure.
                    if (consecutiveFailures[0] >= 8
                            && System.currentTimeMillis() - openedAt >= 8_000L) {
                        submitted[0] = true;
                        ((Timer) event.getSource()).stop();
                        frame.dispose();
                        onFailure.accept(friendlyError(error));
                    }
                    return;
                }
                consecutiveFailures[0] = 0;
                if (!containsLoginCredential(header)) return;
                submitted[0] = true;
                ((Timer) event.getSource()).stop();
                frame.dispose();
                onCookie.accept(header);
            });
        });
        cookiePoll.setInitialDelay(900);

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) {
                cookiePoll.start();
            }

            @Override public void windowClosed(WindowEvent event) {
                cookiePoll.stop();
                if (!disposed[0]) {
                    disposed[0] = true;
                    try {
                        webView.dispose();
                    } catch (Throwable ignored) {
                    }
                }
                if (activeFrame == frame) activeFrame = null;
                if (!submitted[0]) onCancel.run();
            }
        });
        frame.setVisible(true);
    }

    private static boolean containsLoginCredential(String header) {
        if (header == null || header.isEmpty()) return false;
        for (String part : header.split(";")) {
            String pair = part.trim();
            if (pair.startsWith("MUSIC_U=") && pair.length() > "MUSIC_U=".length()) {
                return true;
            }
        }
        return false;
    }

    private static String friendlyError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return "无法启动系统 WebView，请确认已安装 WebKitGTK 4.1";
        }
        if (os.contains("win")) {
            return "无法启动系统 WebView，请确认已安装 WebView2 Runtime";
        }
        return "无法启动系统 WebView，请使用粘贴 Cookie 登录";
    }
}
