package dev.t1m3.qplayer.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Official NetEase website login in Android's app-private system WebView. */
public final class NeteaseWebLoginActivity extends Activity {
    static final String EXTRA_COOKIE_HEADER = "neteaseCookieHeader";

    private static final String LOGIN_URL = "https://music.163.com/#/login";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String COOKIE_URL = "https://music.163.com/";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean completed;

    private final Runnable cookiePoll = new Runnable() {
        @Override public void run() {
            if (completed || webView == null) return;
            String header = CookieManager.getInstance().getCookie(COOKIE_URL);
            if (containsLoginCredential(header)) {
                completed = true;
                Intent result = new Intent();
                result.putExtra(EXTRA_COOKIE_HEADER, header);
                setResult(Activity.RESULT_OK, result);
                clearWebCookies();
                finish();
                return;
            }
            handler.postDelayed(this, 600L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        FrameLayout toolbar = new FrameLayout(this);
        int barHeight = dp(56);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight));

        TextView title = new TextView(this);
        title.setText("登录网易云音乐");
        title.setTextSize(18f);
        title.setTextColor(Color.rgb(32, 32, 32));
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageButton close = new ImageButton(this);
        close.setContentDescription("关闭");
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                barHeight, barHeight, Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.addView(close, closeParams);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        webView.clearCache(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient());

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        // This WebView is only a login handoff. Start clean so changing accounts
        // cannot silently reuse an old MUSIC_U, and erase the browser copy again
        // after player-core has received it for encrypted persistence.
        cookieManager.removeAllCookies(ignored -> {
            if (webView != null) {
                cookieManager.flush();
                webView.loadUrl(LOGIN_URL);
                handler.postDelayed(cookiePoll, 800L);
            }
        });
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(cookiePoll);
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void clearWebCookies() {
        CookieManager manager = CookieManager.getInstance();
        manager.removeAllCookies(ignored -> manager.flush());
    }

    private static boolean containsLoginCredential(String header) {
        if (header == null) return false;
        for (String part : header.split(";")) {
            String pair = part.trim();
            if (pair.startsWith("MUSIC_U=") && pair.length() > 8) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
