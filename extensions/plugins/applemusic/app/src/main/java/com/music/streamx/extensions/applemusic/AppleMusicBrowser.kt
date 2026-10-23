package com.music.streamx.extensions.applemusic

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object AppleMusicBrowser {
    const val HOME = "https://music.apple.com/"
    private val main = Handler(Looper.getMainLooper())
    private val restoreLock = Any()
    class Observer {
        @Volatile private var bearer = ""
        @Volatile private var user = ""
        fun observe(url: String, headers: Map<String, String>) {
            if (!AppleMusicCredentials.isApiUrl(url)) return
            headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
                ?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')
                ?.takeIf(AppleMusicCredentials::bearerIsCurrent)?.let { bearer = it }
            headers.entries.firstOrNull { it.key.equals("Music-User-Token", true) || it.key.equals("media-user-token", true) }
                ?.value?.takeIf { it.isNotBlank() && it.length <= 16384 && it.none(Char::isWhitespace) }?.let { user = it }
            if (user.isBlank() || user == "undefined" || user == "null") sampleCookies()
        }
        fun sampleCookies() {
            AppleMusicCredentials.musicUserCookie(CookieManager.getInstance().getCookie(HOME))?.let { user = it }
        }
        fun candidate(): AppleMusicCredentials? = AppleMusicCredentials(bearer, user).takeIf { it.valid }
    }
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true; loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
    }
    fun client(observer: Observer, onCandidate: (() -> Unit)? = null): WebViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            observer.observe(request.url.toString(), request.requestHeaders)
            if (observer.candidate() != null) onCandidate?.invoke()
            return null
        }
        override fun onPageFinished(view: WebView, url: String) {
            observer.sampleCookies()
            CookieManager.getInstance().flush()
            if (observer.candidate() != null) onCandidate?.invoke()
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && !AppleMusicLoginPolicy.isAppleLoginUrl(request.url.toString())
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
    }
    /** Restores an already-confirmed account from the plugin's persistent web cookies. */
    fun restore(context: Context, session: AppleMusicSession): AppleMusicCredentials = synchronized(restoreLock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Apple Music session restoration must run in the background" }
        check(session.connected) { "Open Apple Music and sign in first" }
        val generation = session.generation
        val observer = Observer()
        val done = CountDownLatch(1)
        val closed = AtomicBoolean()
        val browser = AtomicReference<WebView?>()
        main.post {
            if (closed.get()) return@post
            runCatching {
                WebView(context.applicationContext).also {
                    browser.set(it); configure(it)
                    it.webViewClient = client(observer) { done.countDown() }
                    it.layout(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
                    it.loadUrl("https://music.apple.com/${session.storefront}/home")
                }
            }.onFailure { done.countDown() }
        }
        try { done.await(20, TimeUnit.SECONDS) }
        finally {
            closed.set(true)
            main.post { browser.getAndSet(null)?.let { it.stopLoading(); it.destroy() } }
        }
        val candidate = observer.candidate() ?: error("Apple Music could not restore its browser session. Open your account and sign in again.")
        check(session.restoreCredentials(candidate, generation)) { "Apple Music account changed. Open your account and confirm it again." }
        candidate
    }
    fun clearCookies() {
        main.post { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData() }
    }
}
