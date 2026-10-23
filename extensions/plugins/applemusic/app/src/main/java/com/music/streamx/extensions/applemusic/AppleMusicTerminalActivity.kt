package com.music.streamx.extensions.applemusic

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.json.JSONObject

/** Real Termux terminal UI and PTY, hosted entirely inside the Apple extension. */
class AppleMusicTerminalActivity: Activity() {
    private lateinit var terminal: TerminalView
    private var session: TerminalSession? = null
    private var qemuMode=false
    private val attachHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var fontSize = 16
    private var terminalBg=Color.BLACK;private var terminalInk=Color.WHITE
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        qemuMode=intent.getBooleanExtra("qemu",false)
        val runtime = AppleMusicEmbeddedRuntime(this)
        if(qemuMode && !runtime.ready)runtime.install()
        if(!runtime.ready) { Toast.makeText(this,"Set up the embedded audio tools first",Toast.LENGTH_LONG).show(); finish(); return }
        runtime.prepare()
        val palette = runCatching { JSONObject(intent.getStringExtra("streamx.ui.palette") ?: "{}") }.getOrDefault(JSONObject())
        fun color(key: String, fallback: Int) = runCatching { Color.parseColor(palette.getString(key)) }.getOrDefault(fallback)
        val bg = color("background", Color.BLACK); val ink = color("text", Color.WHITE)
        terminalBg=bg;terminalInk=ink
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(12)) }
        fun key(label: String, action: ()->Unit) = TextView(this).apply {
            text=label;setTextColor(ink);textSize=16f;gravity=Gravity.CENTER;setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener {action()}
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back);imageTintList=android.content.res.ColorStateList.valueOf(ink)
            background=android.graphics.drawable.GradientDrawable().apply {setColor(color("surfaceVariant",0xff1c1c1e.toInt()));cornerRadius=dp(40).toFloat()}
            setPadding(dp(14),dp(14),dp(14),dp(14));contentDescription="Back";setOnClickListener {finish()}
        },LinearLayout.LayoutParams(dp(52),dp(52)))
        header.addView(TextView(this).apply {text="Terminal";setTextColor(ink);textSize=22f;typeface=resources.getFont(R.font.sf_pro_display_bold);setPadding(dp(12),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(key("Keyboard") { terminal.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminal,InputMethodManager.SHOW_IMPLICIT) })
        root.addView(header)
        root.addView(TextView(this).apply {text=if(qemuMode)"Embedded QEMU · Apple sign-in and 2FA stay in this terminal" else "Embedded audio tools · type amdl --help";setTextColor(color("secondary",Color.GRAY));textSize=13f;setPadding(dp(18),0,dp(18),dp(10))})
        terminal=TerminalView(this,null).apply {isFocusable=true;isFocusableInTouchMode=true;setBackgroundColor(bg);setTextSize((fontSize*resources.displayMetrics.scaledDensity).toInt());setPadding(dp(8),dp(4),dp(8),0)}
        terminal.setTerminalViewClient(viewClient)
        root.addView(terminal,LinearLayout.LayoutParams(-1,0,1f))
        val keys=LinearLayout(this)
        for((label,value) in listOf("Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "^C" to "\u0003"))
            keys.addView(key(label) { session?.write(value) },LinearLayout.LayoutParams(0,-2,1f))
        root.addView(keys)
        setContentView(root)
        root.setOnApplyWindowInsetsListener {view,insets->if(Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom)};insets}
        root.requestApplyInsets()
        window.statusBarColor=bg;window.navigationBarColor=bg
        if(Build.VERSION.SDK_INT>=30) {
            val flags=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(Color.luminance(bg)>.5)flags else 0,flags)
        }
        if(qemuMode) {
            startForegroundService(android.content.Intent(this,AppleMusicQemuService::class.java).putExtra("login",intent.getBooleanExtra("login",false)))
            fun attach(attempt: Int) {
                if(isDestroyed || isFinishing)return
                val service=AppleMusicQemuService.current
                val active=service?.session
                if(active!=null) {
                    session=active;service.listener=sessionClient
                    terminal.attachSession(active);terminal.requestFocus()
                } else if(attempt<50)attachHandler.postDelayed({attach(attempt+1)},100)
                else Toast.makeText(this,"QEMU could not start. Check guest installation.",Toast.LENGTH_LONG).show()
            }
            attach(0)
        } else {
        val environment = arrayOf("HOME=${runtime.home.absolutePath}", "TMPDIR=${runtime.home.absolutePath}/tmp", "ENV=${runtime.home.absolutePath}/shell.rc",
            "PATH=/system/bin:/system/xbin", "TERM=xterm-256color", "LANG=en_US.UTF-8")
        val download = intent.getStringExtra("music_url")?.let {AppleMusicTermuxScripts.validateMusicUrl(it)}
        val initial = "printf 'Apple Music embedded terminal\\r\\nAMDL ready. Type amdl --help.\\r\\n'; " +
            (download?.let { AppleMusicTermuxScripts.quote(runtime.downloader.absolutePath) + " " + AppleMusicTermuxScripts.quote(it) + "; " } ?: "") + "exec /system/bin/sh -i"
        val created = TerminalSession("/system/bin/sh", runtime.home.absolutePath,
            arrayOf("/system/bin/sh","-c",initial),
            environment, 2000, sessionClient)
        session=created
        terminal.attachSession(created)
        terminal.requestFocus()
        }
    }

    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private val sessionClient = object: TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {terminal.onScreenUpdated()}
        override fun onTitleChanged(session: TerminalSession) {}
        override fun onSessionFinished(session: TerminalSession) {terminal.onScreenUpdated()}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal",text))}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val text=(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this@AppleMusicTerminalActivity)?.toString() ?: return
            session?.emulator?.paste(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {terminal.onScreenUpdated()}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession,pid: Int) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String,message: String) {}
        override fun logWarn(tag: String,message: String) {}
        override fun logInfo(tag: String,message: String) {}
        override fun logDebug(tag: String,message: String) {}
        override fun logVerbose(tag: String,message: String) {}
        override fun logStackTraceWithMessage(tag: String,message: String,e: Exception) {}
        override fun logStackTrace(tag: String,e: Exception) {}
    }
    private val viewClient = object: TerminalViewClient {
        override fun onScale(scale: Float): Float {fontSize=(fontSize*scale).toInt().coerceIn(10,24);terminal.setTextSize((fontSize*resources.displayMetrics.scaledDensity).toInt());return 1f}
        override fun onSingleTapUp(event: MotionEvent) {(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminal,InputMethodManager.SHOW_IMPLICIT)}
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int,e: KeyEvent,session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int,e: KeyEvent) = false
        override fun onLongPress(e: MotionEvent) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int,ctrlDown: Boolean,session: TerminalSession) = false
        override fun onEmulatorSet() {
            session?.emulator?.mColors?.mCurrentColors?.let { colors->colors[256]=terminalInk;colors[257]=terminalBg;colors[258]=terminalInk }
            terminal.invalidate()
        }
        override fun logError(tag: String,message: String) {}
        override fun logWarn(tag: String,message: String) {}
        override fun logInfo(tag: String,message: String) {}
        override fun logDebug(tag: String,message: String) {}
        override fun logVerbose(tag: String,message: String) {}
        override fun logStackTraceWithMessage(tag: String,message: String,e: Exception) {}
        override fun logStackTrace(tag: String,e: Exception) {}
    }
    override fun onDestroy() {
        // Close the interactive shell and its foreground job. The lossless service
        // has its own process, so leaving this terminal never stops playback.
        attachHandler.removeCallbacksAndMessages(null)
        if(qemuMode) {
            AppleMusicQemuService.current?.let {if(it.listener===sessionClient)it.listener=null}
        } else session?.let { runCatching {if(it.pid>0)android.system.Os.kill(-it.pid,android.system.OsConstants.SIGTERM)};it.finishIfRunning() }
        session=null
        super.onDestroy()
    }
}
