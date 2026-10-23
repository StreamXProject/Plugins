package com.music.streamx.extensions.applemusic

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*

/** Guided sign-in. Guest output and credentials never enter saved instance state or logs. */
class AppleMusicWrapperSetupActivity: Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var appearance: AppleMusicAppearance
    private var awaitingAccountReset=false
    private var rendered: AppleMusicWrapperSetup.State?=null
    private val poll=object: Runnable {override fun run(){
        if(isFinishing || isDestroyed)return
        val state=AppleMusicWrapperSetup.state
        if(awaitingAccountReset && state.stage==AppleMusicWrapperSetup.Stage.READY){handler.postDelayed(this,300);return}
        awaitingAccountReset=false
        if(state.stage==AppleMusicWrapperSetup.Stage.READY) {Toast.makeText(this@AppleMusicWrapperSetupActivity,"Local lossless playback is ready",Toast.LENGTH_LONG).show();finish();return}
        if(state!=rendered){rendered=state;render(state)}
        handler.postDelayed(this,300)
    }}
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        appearance=AppleMusicAppearance(this)
        val a=appearance
        val root=android.widget.FrameLayout(this).apply {setBackgroundColor(a.bg)}
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(84),dp(20),dp(24))}
        content.addView(TextView(this).apply {includeFontPadding=false;if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false;text="Apple Music on this device";textSize=32f;typeface=a.bold;setTextColor(a.ink);setPadding(0,dp(16),0,dp(24))})
        body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        content.addView(body)
        root.addView(ScrollView(this).apply {isFillViewport=true;addView(content)},android.widget.FrameLayout.LayoutParams(-1,-1))
        root.addView(AppleMusicSettingsFade(this,a.bg),android.widget.FrameLayout.LayoutParams(-1,dp(110),Gravity.TOP))
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back);imageTintList=android.content.res.ColorStateList.valueOf(a.ink)
            background=a.touch(a.bg,24);setPadding(dp(12),dp(12),dp(12),dp(12));contentDescription="Back";setOnClickListener{finish()}
        },android.widget.FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.START).apply {leftMargin=dp(16);topMargin=dp(8)})
        setContentView(root)
        root.setOnApplyWindowInsetsListener {v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)};i};root.requestApplyInsets()
        window.statusBarColor=a.bg;window.navigationBarColor=a.bg
        if(Build.VERSION.SDK_INT>=30) {
            val flags=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(Color.luminance(a.bg)>.5)flags else 0,flags)
        }
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){finish()}
        if(intent.getBooleanExtra("change_account",false)) {
            awaitingAccountReset=true
            startForegroundService(Intent(this,AppleMusicQemuService::class.java).setAction("change_account"))
        }
        else if(AppleMusicWrapperSetup.state.stage==AppleMusicWrapperSetup.Stage.IDLE)connect()
    }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    private fun connect(){startForegroundService(Intent(this,AppleMusicQemuService::class.java).setAction("setup"))}
    private fun render(state: AppleMusicWrapperSetup.State) {
        body.removeAllViews()
        val a=appearance
        body.addView(TextView(this).apply {includeFontPadding=false;if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false;text=state.message;setTextColor(a.ink);typeface=a.regular;textSize=17f;background=a.shape(a.surface);setPadding(dp(18),dp(18),dp(18),dp(18));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE})
        body.addView(Space(this),LinearLayout.LayoutParams(1,dp(20)))
        fun field(hintText: String,kind: Int): EditText {
            body.addView(TextView(this).apply {includeFontPadding=false;if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false;text=hintText;textSize=13f;typeface=a.bold;setTextColor(a.muted);setPadding(dp(4),dp(12),0,dp(8))})
            return EditText(this).apply {
                includeFontPadding=false;if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false
                hint=hintText;inputType=kind;typeface=a.regular;textSize=17f
                setTextColor(a.ink);setHintTextColor(a.muted);setPadding(dp(18),dp(14),dp(18),dp(14))
                background=android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused),a.shape(a.surface).apply {setStroke(dp(2),a.accent)})
                    addState(intArrayOf(),a.shape(a.surface))
                }
                if(Build.VERSION.SDK_INT>=29)textCursorDrawable?.setTint(a.accent)
                isSingleLine=true;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            }.also {body.addView(it,LinearLayout.LayoutParams(-1,dp(58)))}
        }
        fun action(label: String,click: ()->Unit){body.addView(TextView(this).apply {includeFontPadding=false;if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false;
            text=label;typeface=a.bold;textSize=15f;setTextColor(a.accent);gravity=Gravity.CENTER;setPadding(dp(16),0,dp(16),0)
            background=a.touch(a.surface,12);setOnClickListener{click()}
        },LinearLayout.LayoutParams(-2,dp(48)).apply {topMargin=dp(12);gravity=Gravity.END})}
        fun error(message: String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
        when(state.stage) {
            AppleMusicWrapperSetup.Stage.IDLE -> action("Connect"){connect()}
            AppleMusicWrapperSetup.Stage.SIGN_IN -> {
                val account=field("Apple Account",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                val secret=field("Password",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                action("Sign in") {
                    val user=account.text.toString();val password=secret.text.toString()
                    runCatching {requireNotNull(AppleMusicQemuService.current).signIn(user,password)}
                        .onSuccess {secret.text.clear();account.text.clear()}.onFailure {error(it.message?:"Could not sign in")}
                }
            }
            AppleMusicWrapperSetup.Stage.TWO_FACTOR -> {
                val code=field("Six-digit verification code",InputType.TYPE_CLASS_NUMBER)
                action("Verify") {runCatching {requireNotNull(AppleMusicQemuService.current).submitCode(code.text.toString().trim())}.onSuccess{code.text.clear()}.onFailure{error(it.message?:"Invalid verification code")}}
            }
            AppleMusicWrapperSetup.Stage.ERROR -> {
                action("Try connection again"){connect()}
                action("Sign in again") {AppleMusicWrapperSetup.state=AppleMusicWrapperSetup.State(AppleMusicWrapperSetup.Stage.SIGN_IN,"Step 1 of 2 · Sign in to Apple Music")}
            }
            else -> body.addView(ProgressBar(this).apply {indeterminateTintList=android.content.res.ColorStateList.valueOf(a.accent)},LinearLayout.LayoutParams(dp(40),dp(40)).apply {gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(20)})
        }
        body.addView(TextView(this).apply {includeFontPadding=false;if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false;text="1. Sign in securely\n2. Start and verify your saved session\n\nYou can leave this screen. Preparation continues in the background.";textSize=15f;setTextColor(a.muted);typeface=a.regular;setPadding(0,dp(28),0,0)})
    }
    override fun onResume(){super.onResume();handler.post(poll)}
    override fun onPause(){handler.removeCallbacks(poll);super.onPause()}
    override fun onDestroy(){if(isFinishing)AppleMusicQemuService.current?.cancelAccountChange();super.onDestroy()}
    @Deprecated("Legacy Android") override fun onBackPressed(){finish()}
}
