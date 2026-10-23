package com.music.streamx.extensions.spotify;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebStorage;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the signed-in web player's own requests; credentials stay in this APK's memory. */
final class SpotifySession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Credentials cached;
    private static volatile Credentials capturing;
    private static volatile String captureStatus = "not started";
    private static volatile long cachedGeneration = -1;
    private static volatile long failedUntil;
    private static volatile String lastFailure;
    private static Credentials failed(String reason) {
        failedUntil = System.currentTimeMillis() + 15_000; lastFailure = reason;
        throw new IllegalStateException(reason);
    }
    static final class Credentials {
        volatile String bearer, client;
        long expires;
        volatile long nativeExpires;
        WebView browser;
    }
    static String diagnostics() {
        Credentials session = cached != null ? cached : capturing;
        return "API browser session: " + captureStatus
            + "\nSigned-in bearer: " + (session != null && session.bearer != null ? "present (redacted)" : "not captured")
            + "\nClient token: " + (session != null && session.client != null ? "present (redacted)" : "not captured");
    }
    static void invalidate() {
        Credentials previous = cached;
        cached = null; cachedGeneration = -1; failedUntil = 0; captureStatus = "waiting for connection";
        if (previous != null) destroy(previous.browser);
    }
    private static void destroy(WebView browser) {
        if (browser != null) MAIN.post(() -> { browser.stopLoading(); browser.destroy(); });
    }
    static synchronized Credentials obtain(Context context) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Spotify requests must run in the background");
        long generation = context.getSharedPreferences("spotify_session", 0).getLong("generation", 0);
        if (!context.getSharedPreferences("spotify_session", 0).getBoolean("confirmed", false)) throw new IllegalStateException("Open Spotify account and sign in first");
        if (cached != null && cachedGeneration == generation && cached.expires > System.currentTimeMillis()) return cached;
        if (failedUntil > System.currentTimeMillis()) throw new IllegalStateException(lastFailure);
        Credentials previous = cached;
        if (previous != null) destroy(previous.browser);
        cached = null;
        Credentials found = new Credentials();
        capturing = found; captureStatus = "loading signed-in web player";
        CountDownLatch done = new CountDownLatch(1); AtomicBoolean closed = new AtomicBoolean();
        WebView[] browser = new WebView[1];
        MAIN.post(() -> {
            if (closed.get()) return;
            try {
                CookieManager.getInstance().setAcceptCookie(true);
                String cookies = CookieManager.getInstance().getCookie("https://open.spotify.com/");
                if (cookies == null || !cookies.contains("sp_dc=")) { done.countDown(); return; }
                // The renderer shares this origin. Clearing its storage here also resets
                // the live Spotify Connect device, so only explicit sign-out clears it.
                WebView view = new WebView(context.getApplicationContext()); browser[0] = view; found.browser = view;
                view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true);
                view.getSettings().setAllowFileAccess(false); view.getSettings().setAllowContentAccess(false);
                view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                // Restore the same player profile used to confirm the account. A mobile
                // landing page can stop at an install prompt without issuing account requests.
                SpotifyBrowser.configureDesktopRenderer(view);
                view.setWebChromeClient(new android.webkit.WebChromeClient() {
                    @Override public void onPermissionRequest(android.webkit.PermissionRequest request) { SpotifyBrowser.permission(request); }
                });
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, HOOK + SpotifyPlayback.continuePromptScript(), java.util.Collections.singleton("https://open.spotify.com"));
                }
                view.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                        String host = request.getUrl().getHost();
                        return request.isForMainFrame() && (!"https".equals(request.getUrl().getScheme()) || !"open.spotify.com".equals(host));
                    }
                    @Override public void onPageStarted(WebView web, String url, android.graphics.Bitmap icon) { web.evaluateJavascript(HOOK + SpotifyPlayback.continuePromptScript(), null); }
                    @Override public void onPageFinished(WebView web, String url) { web.evaluateJavascript(HOOK + SpotifyPlayback.continuePromptScript(), null); }
                    @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                        String host = request.getUrl().getHost();
                        if ("https".equals(request.getUrl().getScheme()) &&
                                ("api.spotify.com".equals(host) || "api-partner.spotify.com".equals(host) ||
                                 "spclient.wg.spotify.com".equals(host) || host != null && host.endsWith("-spclient.spotify.com"))) {
                            request.getRequestHeaders().forEach((key, value) -> {
                                if (key.equalsIgnoreCase("Client-Token")) found.client = value;
                            });
                        }
                        return null;
                    }
                });
                Runnable poll = new Runnable() {
                    public void run() {
                        if (closed.get()) return;
                        if (!"open.spotify.com".equals(Uri.parse(view.getUrl() == null ? "" : view.getUrl()).getHost())) { MAIN.postDelayed(this, 250); return; }
                        view.evaluateJavascript("window.__streamxRestoreSession?.(); window.__streamxSession || null", raw -> {
                            if (closed.get()) return;
                            try {
                                Object parsed = new JSONTokener(raw == null ? "null" : raw).nextValue();
                                if (parsed instanceof JSONObject) {
                                    JSONObject data = (JSONObject) parsed;
                                    if (!data.optBoolean("anonymous", true) && !data.optString("bearer").isEmpty()) {found.bearer = data.getString("bearer");found.nativeExpires=data.optLong("expires_at",0);}
                                    if (!data.optString("client").isEmpty()) found.client = data.getString("client");
                                    // Publish a header-observed client token back into the same page's session.
                                    if (found.client != null) view.evaluateJavascript("window.__streamxSession.client = " + JSONObject.quote(found.client), null);
                                }
                            } catch (Exception ignored) { }
                            if (found.bearer != null && found.client != null) done.countDown();
                        });
                        if (found.bearer != null && found.client != null) done.countDown();
                        if (!closed.get() && done.getCount() != 0) MAIN.postDelayed(this, 250);
                    }
                };
                view.loadUrl("https://open.spotify.com/"); MAIN.postDelayed(poll, 250);
            } catch (Exception ignored) { done.countDown(); }
        });
        try { done.await(20, TimeUnit.SECONDS); }
        finally {
            closed.set(true);
            capturing = null; captureStatus = found.bearer != null && found.client != null ? "signed-in credentials captured" : "capture failed: bearer " + (found.bearer == null ? "missing" : "present") + ", client " + (found.client == null ? "missing" : "present");
            if (found.bearer == null || found.client == null) destroy(browser[0]);
        }
        if (found.bearer == null || found.client == null) return failed("Spotify could not establish a signed-in browser session. Open your account and sign in again.");
        if (generation != context.getSharedPreferences("spotify_session", 0).getLong("generation", 0) || !context.getSharedPreferences("spotify_session", 0).getBoolean("confirmed", false)) {
            destroy(found.browser); throw new IllegalStateException("Spotify account changed during connection");
        }
        long now=System.currentTimeMillis();
        if(found.nativeExpires==0) found.nativeExpires=now+TimeUnit.MINUTES.toMillis(2);
        else if(found.nativeExpires<=now+TimeUnit.SECONDS.toMillis(60)) {
            destroy(found.browser);return failed("Spotify browser authorization is expiring. Reload your account and try again.");
        }
        found.expires=Math.min(now+TimeUnit.MINUTES.toMillis(10),found.nativeExpires-TimeUnit.SECONDS.toMillis(45));
        isolateMetadataBrowser(found);
        cached = found; cachedGeneration = generation; return found;
    }
    private static void isolateMetadataBrowser(Credentials credentials) throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        AtomicBoolean expired=new AtomicBoolean();
        MAIN.post(() -> {
            if(expired.get())return;
            WebView view=credentials.browser;
            view.stopLoading();
            // Retain the same HTTPS origin for authenticated fetch, but retire Spotify's
            // full app and its competing Connect device. No player scripts run here.
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView web,String url) {
                    if(expired.get())return;
                    String session="{\"anonymous\":false,\"bearer\":"+JSONObject.quote(credentials.bearer)+",\"client\":"+JSONObject.quote(credentials.client)+"}";
                    web.evaluateJavascript("window.__streamxSession="+session+";",raw -> ready.countDown());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView web,WebResourceRequest request) {return true;}
            });
            view.loadDataWithBaseURL("https://open.spotify.com/",
                "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; connect-src https://*.spotify.com\"><title>Spotify metadata</title>",
                "text/html","UTF-8","https://open.spotify.com/");
        });
        boolean completed=ready.await(5,TimeUnit.SECONDS);expired.set(true);
        if(!completed){destroy(credentials.browser);throw new IllegalStateException("Spotify metadata browser did not initialize");}
    }
    static final class Response {
        final int status; final String retryAfter, body;
        Response(int status, String retryAfter, String body) { this.status = status; this.retryAfter = retryAfter; this.body = body; }
    }
    /** Read-only API requests keep the real browser's cookies, TLS, origin and session together. */
    static Response profile(Context context) throws Exception {
        return graph(context, "profileAttributes", "53bcb064f6cd18c23f752bc324a791194d20df612d8e1239c735144ab0399ced", new JSONObject());
    }
    static Response graph(Context context, String operation, String hash, JSONObject variables) throws Exception {
        JSONObject body = new JSONObject().put("operationName", operation).put("variables", variables)
            .put("extensions", new JSONObject().put("persistedQuery", new JSONObject().put("version", 1).put("sha256Hash", hash)));
        return request(context, "https://api-partner.spotify.com/pathfinder/v2/query", "POST", body.toString());
    }
    static void signOut(Context context) {
        android.content.SharedPreferences state=context.getSharedPreferences("spotify_session",0);
        state.edit().putBoolean("confirmed",false).putBoolean("verified",false).remove("name").remove("profile_id").putLong("generation",state.getLong("generation",0)+1).apply();
        invalidate();
        MAIN.post(()->CookieManager.getInstance().removeAllCookies(done->{CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();}));
    }
    static Response createPlaylist(Context context, String body) throws Exception {
        return request(context,"https://spclient.wg.spotify.com/playlist/v2/playlist","POST",body);
    }
    static Response attachPlaylist(Context context, String userId, String body) throws Exception {
        if (userId == null || userId.isBlank() || userId.length() > 256) throw new IllegalArgumentException("Invalid Spotify profile");
        return request(context, "https://spclient.wg.spotify.com/playlist/v2/user/" + Uri.encode(userId) + "/rootlist/changes", "POST", body);
    }
    static Response history(Context context) throws Exception {
        return request(context, "https://api.spotify.com/v1/me/player/recently-played?limit=50", "GET", null);
    }
    private static Response request(Context context, String url, String method, String body) throws Exception {
        Credentials credentials = obtain(context);
        long generation = context.getSharedPreferences("spotify_session", 0).getLong("generation", 0);
        String key = "r" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        Response[] response = new Response[1];
        String[] error = new String[1];
        WebView view = credentials.browser;
        MAIN.post(() -> {
            if (closed.get()) return;
            if (cached != credentials || !"open.spotify.com".equals(Uri.parse(view.getUrl() == null ? "" : view.getUrl()).getHost())) {
                error[0] = "Spotify browser session changed. Reconnect your account."; done.countDown(); return;
            }
            String script = "(() => { const replies = window.__streamxReplies = window.__streamxReplies || {}; const key = " + JSONObject.quote(key) + ";" +
                "const session = window.__streamxSession; if (!session || session.anonymous !== false || !session.bearer || !session.client) { replies[key] = {error:'Spotify session is not signed in'}; return; }" +
                "const controllers = window.__streamxControllers = window.__streamxControllers || {}; const controller = controllers[key] = new AbortController(); const timeout = setTimeout(() => controller.abort(), 15000);" +
                "fetch(" + JSONObject.quote(url) + ", {method:" + JSONObject.quote(method) + ", " + (body == null ? "" : "body:" + JSONObject.quote(body) + ", ") + "credentials:'include', signal:controller.signal, headers:{Authorization:'Bearer ' + session.bearer, 'Client-Token':session.client, Accept:'application/json', 'Content-Type':'application/json', 'App-Platform':'WebPlayer'}})" +
                ".then(async r => { const body = await r.text(); if (!controllers[key]) return; replies[key] = body.length > 1048576 ? {error:'Spotify response exceeded the size limit'} : {status:r.status,retryAfter:r.headers.get('Retry-After'),body}; })" +
                ".catch(() => {if (!controllers[key]) return; replies[key] = {error:'Spotify could not reach the account API from this browser session. Reconnect your account.'};}).finally(() => { clearTimeout(timeout); delete controllers[key]; }); })();";
            view.evaluateJavascript(script, null);
            Runnable poll = new Runnable() {
                public void run() {
                    if (closed.get()) return;
                    if (cached != credentials) { error[0] = "Spotify browser session changed"; done.countDown(); return; }
                    view.evaluateJavascript("window.__streamxReplies && window.__streamxReplies[" + JSONObject.quote(key) + "] || null", raw -> {
                        if (closed.get()) return;
                        try {
                            Object value = new JSONTokener(raw == null ? "null" : raw).nextValue();
                            if (value instanceof JSONObject) {
                                JSONObject item = (JSONObject) value;
                                if (item.has("error")) error[0] = item.getString("error");
                                else response[0] = new Response(item.getInt("status"), item.isNull("retryAfter") ? null : item.optString("retryAfter"), item.optString("body"));
                                done.countDown();
                            }
                        } catch (Exception ignored) { }
                        if (done.getCount() != 0 && !closed.get()) MAIN.postDelayed(this, 150);
                    });
                }
            };
            MAIN.postDelayed(poll, 100);
        });
        try { done.await(20, TimeUnit.SECONDS); }
        finally {
            closed.set(true);
            MAIN.post(() -> {
                if (cached == credentials) view.evaluateJavascript("(() => { const key = " + JSONObject.quote(key) + "; if(window.__streamxControllers && window.__streamxControllers[key]) { window.__streamxControllers[key].abort(); delete window.__streamxControllers[key]; } if(window.__streamxReplies) delete window.__streamxReplies[key]; })()", null);
            });
        }
        if (generation != context.getSharedPreferences("spotify_session", 0).getLong("generation", 0)) throw new IllegalStateException("Spotify account changed");
        if (error[0] != null) throw new IllegalStateException(error[0]);
        if (response[0] == null) throw new IllegalStateException("Spotify account request timed out. Try again.");
        return response[0];
    }

    // Read only Spotify token responses in the top-level player, with no JavaScript bridge exposed to frames.
    private static final String HOOK = """
        (() => {
          if (window.__streamxHook) return;
          window.__streamxHook = true;
          // This browser is for metadata only; it must never compete with the audio renderer.
          HTMLMediaElement.prototype.play=function() {
            this.pause(); return Promise.reject(new DOMException('Metadata browser has no audio transport','NotAllowedError'));
          };
          window.__streamxSession = window.__streamxSession || {};
          const receive = (url, body) => {
            if (!String(url).includes('/api/token') && !String(url).includes('clienttoken')) return;
            try {
              const value = JSON.parse(body), state = window.__streamxSession;
              if (String(url).includes('/api/token') && value.accessToken && value.isAnonymous === false) {
                state.bearer = value.accessToken; state.anonymous = false; state.expires_at = Number(value.accessTokenExpirationTimestampMs) || 0;
              }
              if (String(url).includes('clienttoken') && value.granted_token) state.client = value.granted_token.token;
            } catch (_) {}
          };
          // Some page loads embed the initial session instead of fetching /api/token.
          // Read that same signed-in response shape; never persist its credential values.
          window.__streamxRestoreSession=() => {
            for(const script of document.querySelectorAll('script[type="application/json"]')) {
              try {
                const value=JSON.parse(script.textContent||'null');
                if(value?.accessToken && value.isAnonymous===false)
                  receive('/api/token',JSON.stringify(value));
              }catch(_){}
            }
          };
          const original = window.fetch;
          window.fetch = function(input, options) {
            const url = input && input.url || input;
            const answer = original.apply(this, arguments);
            if (String(url).includes('/api/token') || String(url).includes('clienttoken')) answer.then(r => r.clone().text().then(body => receive(url, body))).catch(() => {});
            return answer;
          };
          const open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(method,url) { this.__streamxUrl = url; return open.apply(this,arguments); };
          XMLHttpRequest.prototype.send = function() { this.addEventListener('load', () => receive(this.__streamxUrl, this.responseText)); return send.apply(this,arguments); };
        })();
        """;
}
