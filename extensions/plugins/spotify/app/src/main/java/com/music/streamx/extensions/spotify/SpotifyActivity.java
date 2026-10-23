package com.music.streamx.extensions.spotify;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** Spotify owns the form. This APK owns only its isolated WebView cookie jar and confirmation UI. */
public final class SpotifyActivity extends Activity {
    private static final String PLAYER = "https://open.spotify.com/";
    private static final String LOGIN = "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F";
    private LinearLayout root;
    private WebView web;
    private TextView confirm;
    private TextView pageStatus;
    private ProgressBar progress;
    private SharedPreferences state;
    private Typeface regular, bold, heavy;
    private int background = Color.BLACK, surface = 0xff0d0d0f, variant = 0xff1c1c1e;
    private int foreground = Color.WHITE, secondary = 0xff8e8e93, accent = Color.WHITE;
    private int radius = 22;
    private boolean dark = true;
    private boolean browserConnecting;
    private String playbackTrack;
    private TextView diagnostics;
    private TextView connectionStatus;
    private ScrollView diagnosticPanel;
    private boolean isDesktopSite = true;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = getSharedPreferences("spotify_session", MODE_PRIVATE);
        isDesktopSite = state.getBoolean("desktop_site", true);
        regular = getResources().getFont(R.font.sf_pro_display_regular);
        bold = getResources().getFont(R.font.sf_pro_display_bold);
        heavy = getResources().getFont(R.font.sf_pro_display_heavy);
        readAppearance();
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        playbackTrack = SpotifyPlayback.track(getIntent().getStringExtra("streamx.playback.track_id"));
        showBrowser(true);
    }
    private void readAppearance() {
        dark = getIntent().getBooleanExtra("streamx.ui.dark", true);
        try {
            String value = getIntent().getStringExtra("streamx.ui.palette");
            if (value != null && value.length() < 2048) {
                JSONObject palette = new JSONObject(value);
                background = color(palette, "background", background);
                surface = color(palette, "surface", surface);
                variant = color(palette, "surfaceVariant", variant);
                foreground = color(palette, "text", foreground);
                secondary = color(palette, "secondary", secondary);
                accent = color(palette, "accent", accent);
            }
            radius = Math.max(0, Math.min(32, getIntent().getIntExtra("streamx.ui.cornerRadiusDp", 22)));
        } catch (Exception ignored) { /* Untrusted activity intents never prevent login. */ }
    }
    private int color(JSONObject palette, String key, int fallback) {
        String value = palette.optString(key);
        return value.matches("#[0-9a-fA-F]{6}") ? Color.parseColor(value) : fallback;
    }
    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private void gap(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private TextView text(String value, int size, int color, Typeface font) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(font);
        return view;
    }
    private GradientDrawable shape(int fill, int corners, boolean border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(corners));
        if (border) drawable.setStroke(dp(1), variant); return drawable;
    }
    private void newRoot() {
        root = column(); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets padding = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }
    private TextView compactAction(String label, Runnable click) {
        TextView view = text(label, 14, foreground, bold);
        view.setGravity(Gravity.CENTER); view.setMinHeight(dp(48));
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), shape(background, 8, false), shape(Color.WHITE, 8, false)));
        view.setOnClickListener(v -> click.run()); return view;
    }
    private void toolbar(String title, Runnable back) {
        LinearLayout bar = row(); bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView close = new ImageView(this); close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(foreground)); close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setContentDescription("Close");
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), shape(background, 24, false), shape(Color.WHITE, 24, false)));
        close.setOnClickListener(v -> back.run()); bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        titles.addView(text(title, 16, foreground, bold));
        pageStatus = text("Sign in, then use this profile.", 13, secondary, regular); titles.addView(pageStatus);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        confirm = compactAction("Use this profile", this::connectProfile); bar.addView(confirm);
        root.addView(bar);
    }
    private void toggleDesktopSite() {
        isDesktopSite = !isDesktopSite;
        state.edit().putBoolean("desktop_site", isDesktopSite).apply();
        if (web != null) {
            SpotifyBrowser.configureDesktop(web, isDesktopSite);
            web.reload();
        }
    }
    private boolean savedSession() { return state.getBoolean("confirmed", false) && state.getBoolean("verified", false) && hasSessionCookie(); }
    private boolean hasSessionCookie() {
        String cookies = CookieManager.getInstance().getCookie("https://open.spotify.com/");
        if (cookies == null) return false;
        for (String entry : cookies.split(";")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("sp_dc") && !pair[1].isEmpty()) return true;
        }
        return false;
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void showBrowser(boolean existing) {
        disposeWeb(); newRoot(); toolbar(playbackTrack == null ? "Sign in to Spotify" : "Spotify player", this::finish);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setProgressTintList(ColorStateList.valueOf(accent));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        web = new WebView(this); web.setBackgroundColor(background);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        SpotifyBrowser.configureDesktop(web, isDesktopSite);
        if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web,SpotifyPlayback.continuePromptScript()+SpotifyTouchPlayback.script(),java.util.Collections.singleton("https://open.spotify.com"));
        if (playbackTrack != null) settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSafeBrowsingEnabled(true); settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) { SpotifyBrowser.permission(request); }
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl(); String host = uri.getHost();
                if ("https".equals(uri.getScheme()) && host != null && (host.equals("spotify.com") || host.endsWith(".spotify.com"))) return false;
                if ("https".equals(uri.getScheme()) && host != null) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        Toast.makeText(SpotifyActivity.this, "Browser sign-in stays in your browser. Use email here to connect this extension.", Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) { pageStatus.setText("This sign-in method could not be opened."); }
                }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SpotifyPlayback.continuePromptScript(),null);
                view.evaluateJavascript(SpotifyTouchPlayback.script(),null);
                Uri uri = Uri.parse(url == null ? "" : url);
                boolean accountPage = "www.spotify.com".equals(uri.getHost()) && uri.getPath() != null && uri.getPath().contains("/account");
                boolean ready = "https".equals(uri.getScheme()) && (accountPage || "open.spotify.com".equals(uri.getHost())) && hasSessionCookie();
                if (!browserConnecting) { confirm.setEnabled(true); confirm.setAlpha(1); }
                pageStatus.setText(ready ? "Your account is ready to confirm" : (uri.getHost() == null ? "Spotify sign in" : uri.getHost()));
                refreshDiagnostics(view);
                if (playbackTrack != null && "open.spotify.com".equals(uri.getHost()) && uri.getPath() != null && SpotifyPlayback.matchesPath(uri.getPath(), playbackTrack)) startWebPlayback(view, playbackTrack);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); pageStatus.setText("Secure connection failed. Try again."); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) pageStatus.setText("Couldn’t load Spotify. Check your connection and retry.");
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = column(); footer.setPadding(dp(12), 0, dp(12), 0);
        connectionStatus = text("", 13, secondary, regular);
        connectionStatus.setVisibility(View.GONE);
        footer.addView(connectionStatus);
        TextView options = compactAction("⋮", () -> {});
        options.setTextSize(22); options.setContentDescription("Browser options");
        options.setOnClickListener(anchor -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
            menu.getMenu().add("Reload page").setOnMenuItemClickListener(item -> { if (web != null) web.reload(); return true; });
            menu.getMenu().add("Desktop site").setCheckable(true).setChecked(isDesktopSite).setOnMenuItemClickListener(item -> { toggleDesktopSite(); return true; });
            menu.getMenu().add("Session diagnostics").setCheckable(true).setChecked(diagnosticPanel.getVisibility() == View.VISIBLE).setOnMenuItemClickListener(item -> {
                boolean show = diagnosticPanel.getVisibility() != View.VISIBLE;
                diagnosticPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show && web != null) refreshDiagnostics(web);
                return true;
            });
            menu.show();
        });
        ((LinearLayout) root.getChildAt(0)).addView(options, new LinearLayout.LayoutParams(dp(40), dp(48)));
        diagnosticPanel = new ScrollView(this);
        diagnostics = text("", 12, secondary, Typeface.MONOSPACE); diagnostics.setTextIsSelectable(true);
        diagnosticPanel.addView(diagnostics); diagnosticPanel.setVisibility(View.GONE);
        footer.addView(diagnosticPanel, new LinearLayout.LayoutParams(-1, dp(150)));
        root.addView(footer);
        browserConnecting = false;
        WebView browser = web;
        browser.postDelayed(new Runnable() {
            @Override public void run() {
                if (web != browser || isDestroyed()) return;
                if (!browserConnecting) {
                    boolean ready = true;
                    try { SpotifyRateLimit.check(System.currentTimeMillis()); }
                    catch (SpotifyRateLimit.Limited limited) { ready = false; }
                    confirm.setEnabled(ready); confirm.setAlpha(ready ? 1 : .45f);
                }
                browser.evaluateJavascript(SpotifyPlayback.continuePromptScript(),null);
                if (diagnosticPanel.getVisibility() == View.VISIBLE) refreshDiagnostics(browser);
                browser.postDelayed(this, 1500);
            }
        }, 1500);
        String target = playbackTrack == null ? PLAYER : SpotifyPlayback.url(playbackTrack);
        web.loadUrl(hasSessionCookie() && existing ? target : (playbackTrack == null ? LOGIN : "https://accounts.spotify.com/en/login?continue=" + Uri.encode(target)));
    }
    private void startWebPlayback(WebView browser, String track) {
        connectionStatus.setVisibility(View.VISIBLE);
        connectionStatus.setText("Opening the selected Spotify track. If autoplay is blocked, tap Play on Spotify’s page.");
        final int[] attempts = {0};
        Runnable start = new Runnable() {
            @Override public void run() {
                if (web != browser || !track.equals(playbackTrack) || isDestroyed()) return;
                browser.evaluateJavascript(SpotifyPlayback.script(track), raw -> {
                    if (web != browser) return;
                    if ("\"clicked\"".equals(raw) || "\"already requested\"".equals(raw) || "\"already playing\"".equals(raw)) {
                        connectionStatus.setText("Play requested in Spotify. Use Spotify’s controls on this page.");
                    } else if (++attempts[0] < 15) browser.postDelayed(this, 1000);
                    else connectionStatus.setText("Tap Play on Spotify’s page to start this track. Any playback error will appear there.");
                });
            }
        };
        browser.postDelayed(start, 1000);
    }
    private void connectProfile() {
        if (web == null || browserConnecting) return;
        TextView connect = confirm, status = connectionStatus;
        status.setVisibility(View.VISIBLE);
        try { SpotifyRateLimit.check(System.currentTimeMillis()); }
        catch (SpotifyRateLimit.Limited limited) { showCooldown(connect, status, limited); return; }
        if (!hasSessionCookie()) { status.setText("No signed-in Spotify cookie yet. Finish signing in on this page."); return; }
        CookieManager.getInstance().flush();
        long generation = state.getLong("generation", 0) + 1;
        state.edit().putBoolean("confirmed", true).putBoolean("verified", false).putLong("generation", generation).apply();
        SpotifySession.invalidate();
        browserConnecting = true;
        connect.setEnabled(false); connect.setText("Connecting…"); status.setText("Verifying your Spotify profile. This page will stay open.");
        new Thread(() -> {
            try {
                JSONObject profile = new SpotifyApi(getApplicationContext()).account();
                runOnUiThread(() -> {
                    if (isDestroyed() || !connect.isAttachedToWindow() || state.getLong("generation", 0) != generation) return;
                    browserConnecting = false; connect.setText("Use this profile"); connect.setEnabled(true);
                    status.setText("Connected as " + profile.optString("name", "your Spotify profile") + ".");
                    Toast.makeText(this, status.getText(), Toast.LENGTH_SHORT).show();
                    if (playbackTrack == null) { finish(); return; }
                    if (web != null) refreshDiagnostics(web);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !connect.isAttachedToWindow() || state.getLong("generation", 0) != generation) return;
                    browserConnecting = false;
                    // A network failure does not revoke the user's saved account selection.
                    state.edit().putBoolean("verified", false).apply();
                    connect.setEnabled(true); connect.setText("Use this profile");
                    status.setText(failure.getMessage() == null ? "Spotify could not connect. Try again." : failure.getMessage());
                    if (failure instanceof SpotifyRateLimit.Limited) showCooldown(connect, status, (SpotifyRateLimit.Limited) failure);
                    if (web != null) refreshDiagnostics(web);
                });
            }
        }, "spotify-account-connect").start();
    }
    private void refreshDiagnostics(WebView browser) {
        if (web != browser || diagnostics == null || diagnosticPanel.getVisibility() != View.VISIBLE) return;
        StringBuilder cookies = new StringBuilder("Updated: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + "\nValues redacted; names and lengths only.\n");
        for (String origin : new String[]{"https://accounts.spotify.com", "https://www.spotify.com", "https://open.spotify.com"}) {
            cookies.append("\n").append(Uri.parse(origin).getHost()).append(" cookies:\n")
                .append(SpotifyDiagnostics.cookies(CookieManager.getInstance().getCookie(origin)));
        }
        cookies.append("\n").append(SpotifySession.diagnostics()).append("\n").append(SpotifyPlaybackBridge.diagnostics(this)).append("\nAPI verified: ").append(state.getBoolean("verified", false));
        String prefix = cookies.toString();
        Uri uri = Uri.parse(browser.getUrl() == null ? "" : browser.getUrl());
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !(host.equals("spotify.com") || host.endsWith(".spotify.com"))) {
            diagnostics.setText(prefix + "\nStorage: unavailable on this page"); return;
        }
        browser.evaluateJavascript("(() => { const read = s => { try { return Object.keys(s).slice(0,40).map(k => ({key:k.slice(0,100),length:(s.getItem(k)||'').length})); } catch (_) { return 'unavailable'; } }; return {origin:location.origin,local:read(localStorage),session:read(sessionStorage)}; })()", raw -> {
            if (web != browser) return;
            try {
                Object parsed = new org.json.JSONTokener(raw == null ? "null" : raw).nextValue();
                diagnostics.setText(prefix + "\nCurrent page storage (key / character count):\n" + (parsed instanceof JSONObject ? ((JSONObject) parsed).toString(2) : "unavailable"));
            } catch (Exception ignored) { diagnostics.setText(prefix + "\nStorage: unavailable"); }
        });
    }
    private void showCooldown(TextView connect, TextView status, SpotifyRateLimit.Limited limited) {
        connect.setEnabled(false);
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (isDestroyed() || !connect.isAttachedToWindow()) return;
                long seconds = SpotifyRateLimit.remaining(limited.until, System.currentTimeMillis());
                if (seconds == 0) { connect.setEnabled(true); connect.setText("Use this profile"); status.setText("You can try connecting again."); return; }
                connect.setText("Retry in " + seconds + "s");
                status.setText(limited.serverSpecified ? "Spotify requested a cooldown. Your browser sign-in is saved."
                    : "Account verification returned HTTP 429 without a retry time. Your browser sign-in is saved.");
                connect.postDelayed(this, 1000);
            }
        };
        tick.run();
    }
    private void disposeWeb() {
        if (web != null) {
            browserConnecting = false;
            WebView previous = web; web = null;
            previous.stopLoading(); previous.setWebChromeClient(null); previous.setWebViewClient(new WebViewClient());
            if (previous.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) previous.getParent()).removeView(previous);
            previous.destroy();
        }
    }
    private void handleBack() { if (web != null && web.canGoBack()) web.goBack(); else finish(); }
    @Override public void onBackPressed() { handleBack(); }
    @Override protected void onPause() { if (web != null) { web.evaluateJavascript(SpotifyPlayback.transportScript(false), null); web.onPause(); } CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { disposeWeb(); super.onDestroy(); }
}
