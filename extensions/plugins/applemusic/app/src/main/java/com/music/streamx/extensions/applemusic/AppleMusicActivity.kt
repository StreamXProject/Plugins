package com.music.streamx.extensions.applemusic

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Native configuration follows the host palette; Apple owns the actual authentication form. */
class AppleMusicActivity : Activity() {
    private lateinit var api: AppleMusicApi
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var web: WebView? = null
    private var popup: WebView? = null
    private var observer: AppleMusicBrowser.Observer? = null
    private var busy = false
    private var background = Color.BLACK
    private var surface = 0xff1c1c1e.toInt()
    private var text = Color.WHITE
    private var secondary = 0xff8e8e93.toInt()
    private var accent = Color.WHITE
    private var dark = true
    private var radius = 22
    private val regular by lazy { AppleMusicAppearance(this).regular }
    private val bold by lazy { AppleMusicAppearance(this).bold }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = AppleMusicApi(this)
        readAppearance()
        window.statusBarColor = background; window.navigationBarColor = background
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        if(intent.getBooleanExtra("streamx.open_alac",false))showAlac() else showBrowser()
    }
    private fun readAppearance() {
        AppleMusicAppearance(this)
        dark = intent.getBooleanExtra("streamx.ui.dark", true)
        runCatching {
            val raw = intent.getStringExtra("streamx.ui.palette")
            if (raw != null && raw.length < 2048) {
                val palette = JSONObject(raw)
                fun color(key: String, fallback: Int) = runCatching { Color.parseColor(palette.getString(key)) }.getOrDefault(fallback)
                background = color("background", background); surface = color("surfaceVariant", surface)
                text = color("text", text); secondary = color("secondary", secondary); accent = color("accent", accent)
            }
            radius = intent.getIntExtra("streamx.ui.cornerRadiusDp", 22).coerceIn(0, 32)
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 17f, color: Int = text, strong: Boolean = false) = TextView(this).apply {
        includeFontPadding=false;if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false
        this.text = value; textSize = size; setTextColor(color); typeface = if (strong) bold else regular
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun base(title: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0); setBackgroundColor(this@AppleMusicActivity.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close); imageTintList = android.content.res.ColorStateList.valueOf(text)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "Close"; background = AppleMusicAppearance(this@AppleMusicActivity).touch(this@AppleMusicActivity.background,30); setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        titles.addView(label(title, 16f, strong=true).apply {setPadding(0,0,0,dp(2))})
        status=label("Sign in, then use this profile.",13f,secondary).apply {setPadding(0,0,0,0)}
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, dp(8) + bars.top, bars.right, bars.bottom)
            }
            insets
        }
        root.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
    private fun showAlac() {
        startActivity(android.content.Intent(this,AppleMusicAlacActivity::class.java).apply {intent.extras?.let {putExtras(it)}})
        if(intent.getBooleanExtra("streamx.open_alac",false))finish()
    }
    private fun <T> task(message: String, work: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; status.text = message
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess(done).onFailure {
                    status.text = if (it is java.io.IOException) "Could not reach Apple Music. Check your connection and retry."
                    else if (it is org.json.JSONException) "Apple Music returned an unexpected response. Reload the page and try again."
                    else it.message?.take(250) ?: "Apple Music could not connect. Try again."
                }
            }
        }
    }
    private fun showBrowser() {
        closeBrowser()
        base("Sign in to Apple Music")
        val capture = AppleMusicBrowser.Observer(); observer = capture
        val browser = WebView(this); web = browser
        AppleMusicBrowser.configure(browser)
        browser.setBackgroundColor(background)
        fun detected() {
            runOnUiThread {
                if (observer === capture && !busy) status.text = "Account detected. Tap Use this profile to connect."
            }
        }
        browser.webViewClient = AppleMusicBrowser.client(capture, ::detected)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                closePopup()
                val child = WebView(this@AppleMusicActivity); popup = child
                AppleMusicBrowser.configure(child)
                child.webViewClient = AppleMusicBrowser.client(capture, ::detected)
                child.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) { closePopup() }
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                }
                browser.visibility = View.GONE
                root.addView(child, 1, LinearLayout.LayoutParams(-1, 0, 1f))
                (resultMsg.obj as WebView.WebViewTransport).webView = child; resultMsg.sendToTarget()
                return true
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        val confirm=label("Use this profile",14f,strong=true).apply {
            minHeight=dp(48);gravity=Gravity.CENTER
            background=AppleMusicAppearance(this@AppleMusicActivity).touch(this@AppleMusicActivity.background,8)
            setOnClickListener confirmClick@ {
            if(busy)return@confirmClick
            capture.sampleCookies()
            val credentials = capture.candidate()
            if (credentials == null) {
                status.text = "Finish signing in on Apple Music first, then try again."
                return@confirmClick
            }
            val generation = api.session.generation
            task("Verifying your Apple Music account…", { api.verify(credentials) }) { storefront ->
                if (observer !== capture) return@task
                if (!api.session.confirm(credentials, storefront, generation)) {
                    status.text = "Account changed. Reopen sign-in to continue."
                    return@task
                }
                finish()
                Toast.makeText(this@AppleMusicActivity, "Apple Music connected", Toast.LENGTH_SHORT).show()
            }
            }
        }
        (root.getChildAt(0) as LinearLayout).apply {
            addView(confirm,LinearLayout.LayoutParams(-2,-2))
            addView(label("⋮",22f).apply {
                gravity=Gravity.CENTER;contentDescription="Browser options"
                background=AppleMusicAppearance(this@AppleMusicActivity).touch(this@AppleMusicActivity.background,8)
                setOnClickListener { anchor ->
                    PopupMenu(this@AppleMusicActivity,anchor).apply {
                        menu.add("Reload page").setOnMenuItemClickListener {if(!busy)(popup?:browser).reload();true}
                        show()
                    }
                }
            },LinearLayout.LayoutParams(dp(40),dp(48)))
        }
        browser.loadUrl(AppleMusicBrowser.HOME)
    }
    private fun closePopup() {
        popup?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; popup = null
        web?.visibility = View.VISIBLE
    }
    private fun closeBrowser() {
        observer = null
        closePopup()
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; web = null
    }
    private fun back() {
        if (popup != null) { if (popup!!.canGoBack()) popup!!.goBack() else closePopup(); return }
        finish()
    }
    @Deprecated("Legacy Android back callback") override fun onBackPressed() { back() }
    override fun onDestroy() { closeBrowser(); worker.shutdownNow(); super.onDestroy() }
}
