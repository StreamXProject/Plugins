package com.music.streamx.extensions.applemusic

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Settings cards, navigation and edit dialogs follow the host appearance. */
class AppleMusicAlacActivity: Activity() {
    private var bg=Color.BLACK;private var card=0xff1c1c1e.toInt();private var ink=Color.WHITE
    private var muted=0xff909094.toInt();private var accent=Color.WHITE;private var radius=22
    private var material=false
    private val regular by lazy {if(material)android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL) else resources.getFont(R.font.sf_pro_display_regular)}
    private val bold by lazy {if(material)android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) else resources.getFont(R.font.sf_pro_display_bold)}
    private val heavy by lazy {if(material)android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD) else resources.getFont(R.font.sf_pro_display_heavy)}
    private var flat=false
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var headerTitle: TextView
    private val pageHistory=java.util.ArrayDeque<String>()
    private val config get()=AppleMusicAlac(this)
    private val termux get()=AppleMusicTermux(this)
    private val embedded get()=AppleMusicEmbeddedRuntime(this)
    private var wrapperUrl="";private var wrapperUser="amdl";private var wrapperPassword=""
    private var page="home";private var busy=false;private var resumed=false
    private var qemuProgress: TextView?=null
    private var console: TextView?=null
    private var draftUrl="";private var draftToken=""
    private val refresh=object: Runnable {override fun run(){if(!resumed)return;if(page=="qemu")qemuProgress?.text=AppleMusicWrapperSetup.state.message;console?.let {val log=AppleMusicTermuxLog.text(this@AppleMusicAlacActivity).ifBlank {"Task output will appear here."};if(it.text.toString()!=log)it.text=log};main.postDelayed(this,750)}}
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AppleMusicAppearance(this)
        material=intent.getBooleanExtra("streamx.ui.material",false)
        runCatching {val p=JSONObject(intent.getStringExtra("streamx.ui.palette")?:"{}");fun read(k: String,f: Int)=runCatching {Color.parseColor(p.getString(k))}.getOrDefault(f)
            bg=read("background",bg);card=read("surfaceVariant",card);ink=read("text",ink);muted=read("secondary",muted);accent=read("accent",accent)
        }
        radius=intent.getIntExtra("streamx.ui.cornerRadiusDp",22).coerceIn(0,32)
        flat=intent.getStringExtra("streamx.ui.settingsLayout")?.let {it=="FLAT"} ?: false
        window.statusBarColor=bg;window.navigationBarColor=bg
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){back()}
        show("home")
    }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color: Int,r: Int=radius)=GradientDrawable().apply {setColor(color);cornerRadius=dp(r).toFloat()}
    private fun text(value: String,size: Float=17f,color: Int=ink,strong: Boolean=false)=TextView(this).apply {
        text=value;textSize=size;includeFontPadding=false;setTextColor(color);typeface=if(strong)bold else regular
        if(Build.VERSION.SDK_INT>=28)isFallbackLineSpacing=false
    }
    private fun show(target: String,remember: Boolean=true) {
        if(remember && target!=page)pageHistory.addLast(page)
        page=target;console=null;qemuProgress=null
        root=FrameLayout(this).apply {setBackgroundColor(bg)}
        val scroll=ScrollView(this).apply {isFillViewport=true;clipToPadding=false;isVerticalScrollBarEnabled=false}
        body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(84),dp(16),dp(32))}
        headerTitle=text("",if(material)36f else 40f,strong=true).apply {typeface=if(material)regular else heavy;setPadding(dp(4),dp(20),dp(4),dp(8));if(Build.VERSION.SDK_INT>=28)isAccessibilityHeading=true}
        body.addView(headerTitle)
        scroll.addView(body);root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        // The app's top fade is its bottom scrim flipped over: 180dp, with
        // sixteen samples of the same eased alpha curve. It draws over the
        // scrolling page rather than clipping that page below a solid header.
        root.addView(AppleMusicSettingsFade(this,bg),FrameLayout.LayoutParams(-1,dp(180),Gravity.TOP))
        root.addView(ImageView(this).apply {setImageResource(R.drawable.ic_back);imageTintList=android.content.res.ColorStateList.valueOf(ink)
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((ink and 0xffffff) or 0x20000000),
                if(material)null else shape(if(Color.luminance(bg)<.5)0xff0c0c0e.toInt() else card,40).apply {setStroke(dp(1),muted and 0x00ffffff or 0x33000000)},shape(Color.WHITE,40))
            setPadding(dp(12),dp(12),dp(12),dp(12));contentDescription="Back";setOnClickListener{back()}
        },FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.START).apply {leftMargin=dp(16);topMargin=dp(8)})
        setContentView(root)
        root.setOnApplyWindowInsetsListener {view,insets->if(Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom)};insets};root.requestApplyInsets()
        if(Build.VERSION.SDK_INT>=30) {
            val flags=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(Color.luminance(bg)>.5)flags else 0,flags)
        }
        when(target){"connection"->connection();"setup"->setup();"embedded"->embeddedSetup();"qemu"->qemuSetup();"wrapper"->wrapperConnection();"terminal"->terminal();"activity"->activity();else->home()}
        status=text("",15f,muted).apply {setPadding(dp(4),dp(16),dp(4),0);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};body.addView(status)
    }
    private fun title(value: String){headerTitle.text=value}
    private fun group(label: String,block: (LinearLayout)->Unit) {
        body.addView(text(if(flat)label else label.uppercase(),if(flat)20f else 11f,muted,!flat).apply {setPadding(dp(4),dp(24),dp(4),dp(8))})
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;if(!flat){background=shape(card);clipToOutline=true}}
        block(box);body.addView(box)
    }
    private fun divider(box: LinearLayout){if(!flat)box.addView(View(this).apply {setBackgroundColor(ink and 0x00ffffff or 0x16000000)},LinearLayout.LayoutParams(-1,dp(1)).apply {leftMargin=dp(52)})}
    private fun rowIcon(name: String): Int = when {
        name.contains("terminal",true) || name.contains("Termux",true) -> R.drawable.ic_settings_terminal
        name.contains("account",true) || name.contains("Sign in",true) || name=="Username" -> R.drawable.ic_settings_account
        name.contains("download",true) || name.contains("Import",true) || name.contains("Export",true) || name.contains("Install",true) || name.contains("tools",true) -> R.drawable.ic_settings_download
        name.contains("rate",true) || name.contains("ALAC",true) || name.contains("lossless",true) -> R.drawable.ic_settings_audio
        name.contains("activity",true) || name.contains("output",true) -> R.drawable.ic_settings_activity
        else -> R.drawable.ic_settings_connection
    }
    private fun row(box: LinearLayout,name: String,detail: String?=null,enabled: Boolean=true,click: (() -> Unit)?=null,trailing: View?=null): TextView? {
        val row=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(if(flat)4 else 16),dp(12),dp(16),dp(12));minimumHeight=dp(52);alpha=if(enabled)1f else .45f}
        row.addView(ImageView(this).apply {setImageResource(rowIcon(name));imageTintList=android.content.res.ColorStateList.valueOf(ink);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(22),dp(22)).apply {rightMargin=dp(14)})
        val labels=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        labels.addView(text(name).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        val detailView=detail?.let {text(it,14f,muted).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(2),0,0)}.also(labels::addView)}
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        if(trailing!=null)row.addView(trailing) else if(click!=null)row.addView(ImageView(this).apply {setImageResource(R.drawable.ic_settings_chevron);imageTintList=android.content.res.ColorStateList.valueOf(muted)},LinearLayout.LayoutParams(dp(16),dp(16)).apply {leftMargin=dp(12)})
        if(click!=null && enabled){row.background=AppleMusicAppearance(this).touch(android.graphics.Color.TRANSPARENT,if(flat)0 else radius);row.setOnClickListener{if(!busy)click() else Toast.makeText(this,status.text,Toast.LENGTH_SHORT).show()}}
        box.addView(row);return detailView
    }
    private fun button(name: String,primary: Boolean=false,click: () -> Unit) {
        if(material) {
            body.addView(text(name,14f,if(primary)bg else accent,true).apply {
                gravity=Gravity.CENTER;minimumHeight=dp(48);background=AppleMusicAppearance(this@AppleMusicAlacActivity).touch(if(primary)accent else card,28)
                setPadding(dp(24),dp(12),dp(24),dp(12));setOnClickListener{if(!busy)click()}
            },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
            return
        }
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        row(box,name,click=click);body.addView(box,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
    }
    private fun qualityPicker() {
        val dialog=android.app.Dialog(this)
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;background=shape(if(Color.luminance(bg)<.5)0xff111113.toInt() else card,22);setPadding(dp(20),dp(20),dp(20),dp(12))}
        panel.addView(text("Maximum sample rate",22f,strong=true).apply {setPadding(0,0,0,dp(12))})
        listOf(48000,96000,192000).forEach {rate->
            row(panel,"${rate/1000} kHz",click={runCatching {config.setMaxRate(rate)}.onSuccess {dialog.dismiss();show("home",remember=false)}.onFailure {status.text=it.message}},trailing=text(if(rate==config.maxRate)"✓" else "",20f,ink))
        }
        row(panel,"Cancel",click={dialog.dismiss()});dialog.setContentView(panel);dialog.window?.setBackgroundDrawableResource(android.R.color.transparent);dialog.show();dialog.window?.setLayout(resources.displayMetrics.widthPixels-dp(48),-2)
    }
    private fun home() {
        title("Lossless connection")
        group("Connection") {box->
            row(box,"On this device",AppleMusicWrapperSetup.state.message,click={show("qemu")})
            divider(box);row(box,"External bridge",config.externalEndpoint.takeIf {it.isNotBlank()}?:"Server address and access token",click={draftUrl=config.externalEndpoint;draftToken=config.externalToken;show("connection")})
        }
        group("Audio quality") {box->
            row(box,"Maximum sample rate",click={qualityPicker()},trailing=text("${config.maxRate/1000} kHz",17f,muted))
        }
        group("Tools") {box->
            row(box,"Advanced connection settings",click={show("embedded")})
            divider(box);row(box,"Terminal",click={show("terminal")})
            divider(box);row(box,"Connection activity",click={show("activity")})
        }
    }

    private fun connection() {
        title("ALAC connection")
        lateinit var server: TextView;lateinit var secret: TextView
        fun redraw(){server.text=draftUrl.ifBlank {"Add server address"};secret.text=if(draftToken.isBlank())"Add access token" else "Access token saved"}
        group("Connection details") {box->
            server=requireNotNull(row(box,"Server address","",click={edit("Server address","https://…",draftUrl,false){draftUrl=it.trim().trimEnd('/');redraw()}}));divider(box)
            secret=requireNotNull(row(box,"Access token","",click={edit("Access token","Bridge access token",draftToken,true){draftToken=it.trim();redraw()}}))
        };redraw()
        button("Test connection") {work("Connecting…",{config.test(draftUrl,draftToken)}){"ALAC bridge connected"}}
        button("Save connection",true){runCatching {config.saveExternal(draftUrl,draftToken,config.maxRate)}.onSuccess {back();status.text="Connection saved"}.onFailure {status.text=it.message}}
    }
    private fun embeddedSetup() {
        title("On this device")
        group("Audio tools") {box->
            row(box,if(embedded.ready)"Audio tools ready" else "Set up embedded tools",
                if(embedded.ready)"Ready" else "Prepare audio tools",
                click=if(embedded.ready)null else ({work("Preparing audio tools…",{embedded.install()}){show("embedded");"Audio tools ready"}}))
            divider(box);row(box,"Wrapper connection",embedded.wrapper.address.ifBlank {"Server address, username and access token"},click={
                val saved=embedded.wrapper;wrapperUrl=saved.address;wrapperUser=saved.username;wrapperPassword=saved.password;show("wrapper")
            })
            divider(box);row(box,"Apple Music account",if(AppleMusicSession(this).connected)"Connected" else "Not connected",click={startActivity(Intent(this,AppleMusicActivity::class.java).apply {this@AppleMusicAlacActivity.intent.extras?.let {putExtras(it)};removeExtra("streamx.open_alac");putExtra("streamx.open_login",true)})})
        }
        group("Streaming") {box->
            row(box,"Use this connection",if(config.enabled && config.endpoint.contains("12441"))"Selected" else null,click={
                work("Starting lossless streaming…",{embedded.select();config.setEnabled(true)}){pageHistory.clear();show("home",remember=false);"Embedded ALAC streaming enabled"}
            })
            divider(box);row(box,"Terminal",click={openEmbeddedTerminal()})
            divider(box);row(box,"Export saved downloads","Save to a folder on this device",click={
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),52)
            })
            divider(box);row(box,"Connection activity",click={show("activity")})

        }
        group("Advanced") {box->row(box,"Stop streaming",click={embedded.stop();status.text="Streaming stopped"});divider(box);row(box,"Separate Termux app",click={show("setup")})}
    }
    private fun qemuSetup() {
        title("Local lossless playback")
        val state=AppleMusicWrapperSetup.state
        group("On this device") {box->
            qemuProgress=row(box,"Apple Music",state.message)
            if(state.stage!=AppleMusicWrapperSetup.Stage.READY) {
                divider(box)
                row(box,"Set up local playback","Sign in, verify your code if asked, and you’re ready",click={
                    startActivity(Intent(this,AppleMusicWrapperSetupActivity::class.java).apply {intent.extras?.let {putExtras(it)}})
                })
            }
        }
        val runtime = AppleMusicQemuRuntime(this)
        group("Local playback files") {box->
            row(box,"Import binary file",
                if(runtime.installed) "Local playback files are already installed" else "Choose the wrapper-lite QEMU guest ZIP from this device",
                enabled=runtime.available && !runtime.installed,
                click={
                    runCatching {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),53)
                    }.onFailure {status.text=it.message ?: "Could not open the file picker"}
                })
        }
        group("Account") {box->row(box,"Change account","Sign in with a different Apple Account for local playback",enabled=AppleMusicQemuRuntime(this).installed,click={
            startActivity(Intent(this,AppleMusicWrapperSetupActivity::class.java).apply {intent.extras?.let{putExtras(it)};putExtra("change_account",true)})
        })}
        body.addView(text("Setup continues in the background when you leave. Ready means your saved sign-in and the lossless connection have both been verified.",15f,muted).apply {setPadding(dp(4),dp(20),dp(4),dp(12))})
        group("Connection") {box->row(box,"Stop local playback",enabled=AppleMusicQemuService.current!=null,click={
            embedded.stop();stopService(Intent(this,AppleMusicQemuService::class.java));main.postDelayed({if(!isDestroyed)show("qemu",remember=false)},150)
        })}
    }
    private fun openQemuTerminal(login: Boolean) {
        if(login && AppleMusicQemuService.current?.session?.isRunning==true) {
            AlertDialog.Builder(this)
                .setTitle("Wrapper is already running")
                .setMessage("Open its terminal to continue an existing sign-in, or stop the wrapper and start a new sign-in. Stopping interrupts local lossless playback; saved login data is kept.")
                .setPositiveButton("Open terminal") { _, _ -> openQemuTerminal(false) }
                .setNeutralButton("Stop and sign in") { _, _ ->
                    stopService(Intent(this,AppleMusicQemuService::class.java))
                    work("Stopping wrapper…", {
                        val deadline=SystemClock.elapsedRealtime()+5000
                        while(AppleMusicQemuService.current?.session?.isRunning==true && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
                        check(AppleMusicQemuService.current?.session?.isRunning!=true){"Wrapper is still stopping. Try sign-in again shortly."}
                    }) { openQemuTerminal(true); "Finish sign-in in the embedded terminal" }
                }
                .setNegativeButton("Cancel",null)
                .show()
            return
        }
        runCatching {
            check(AppleMusicQemuRuntime(this).installed){"Download the guest files first"}
            if(!embedded.ready)embedded.install()
            startActivity(Intent(this,AppleMusicTerminalActivity::class.java).putExtra("qemu",true).putExtra("login",login).apply {
                this@AppleMusicAlacActivity.intent.getStringExtra("streamx.ui.palette")?.let {putExtra("streamx.ui.palette",it)}
            })
        }.onFailure {status.text=it.message}
    }
    private fun wrapperConnection() {
        title("Wrapper connection")
        lateinit var server: TextView;lateinit var user: TextView;lateinit var secret: TextView
        fun redraw(){server.text=wrapperUrl.ifBlank {"Add wrapper-lite address"};user.text=wrapperUser.ifBlank {"No username"};secret.text=if(wrapperPassword.isBlank())"Add access token" else "Access token saved"}
        group("Connection details") {box->
            server=requireNotNull(row(box,"Server address","",click={edit("Wrapper-lite address","https://…",wrapperUrl,true){value->
                runCatching {AppleMusicWrapperAddress.parse(value,wrapperUser,wrapperPassword)}.onSuccess {parsed->
                    wrapperUrl=parsed.address;wrapperUser=parsed.username;wrapperPassword=parsed.password;redraw()
                }.onFailure {status.text=it.message}
            }}));divider(box)
            user=requireNotNull(row(box,"Username","",click={edit("Username","amdl",wrapperUser,false){wrapperUser=it.trim();redraw()}}));divider(box)
            secret=requireNotNull(row(box,"Access token","",click={edit("Access token","Wrapper password or access token",wrapperPassword,true){wrapperPassword=it;redraw()}}))
        };redraw()
        button("Test wrapper") {work("Connecting to wrapper…",{embedded.test(AppleMusicWrapperAddress.parse(wrapperUrl,wrapperUser,wrapperPassword))}){"Wrapper-lite connected"}}
        button("Save connection",true) {
            runCatching {embedded.save(wrapperUrl,wrapperUser,wrapperPassword)}.onSuccess {back();status.text="Wrapper connection saved"}.onFailure {status.text=it.message}
        }
    }
    private fun openEmbeddedTerminal(url: String?=null) {
        runCatching {
            check(embedded.ready){"Set up embedded tools in On this device first"}
            startActivity(Intent(this,AppleMusicTerminalActivity::class.java).apply {
                this@AppleMusicAlacActivity.intent.getStringExtra("streamx.ui.palette")?.let {putExtra("streamx.ui.palette",it)}
                url?.let {putExtra("music_url",AppleMusicTermuxScripts.validateMusicUrl(it))}
            })
        }.onFailure {status.text=it.message}
    }
    private fun setup() {
        title("On this device")
        group("Terminal") {box->
            row(box,if(termux.installed())"Open Termux" else "Install Termux",if(termux.installed())"Installed on this device" else "Official GitHub releases",click={if(termux.installed())openTermux() else link(AppleMusicTermuxScripts.TERMUX_URL)})
            divider(box);row(box,"Allow commands",if(termux.permitted())"Permission granted" else "Android permission required",click={if(!termux.installed())status.text="Install Termux first" else requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"),41)})
            divider(box);row(box,"Enable Termux integration","One-time command in Termux",click={
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Termux integration",AppleMusicTermuxScripts.ENABLE))
                AlertDialog.Builder(this).setTitle("Enable Termux integration").setMessage("The setup command is copied. Paste it in Termux, run it once, then return here. This enables Termux’s required allow-external-apps property.")
                    .setPositiveButton("Open Termux"){_,_->openTermux()}.setNegativeButton("Cancel",null).show()
            })
        }
        group("Install") {box->
            row(box,"Install audio tools","Update packages, install amdl and the local streaming helper",click={run(AppleMusicTermuxScripts.Task.INSTALL)})
            divider(box);row(box,"Install wrapper package","Choose the Android arm64 wrapper-lite QEMU artifact ZIP",click={startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE),51)})
            divider(box);row(box,"Get wrapper package","Open upstream lite builds; download wrapper-lite-qemu-android-aarch64",click={link(AppleMusicTermuxScripts.WRAPPER_URL)})
            divider(box);row(box,"Install FFmpeg","Optional conversion tools for saved downloads",click={run(AppleMusicTermuxScripts.Task.FFMPEG)})
            divider(box);row(box,"Allow Music folder","Optional Android storage access for downloads",click={run(AppleMusicTermuxScripts.Task.STORAGE)})
        }
        group("Local streaming") {box->
            row(box,"Sign in to Apple Music","Private login in the Termux terminal",click={run(AppleMusicTermuxScripts.Task.LOGIN)})
            divider(box);row(box,"Start local wrapper","Keep its Termux session running while listening",click={run(AppleMusicTermuxScripts.Task.START_WRAPPER)})
            divider(box);row(box,"Start streaming helper","Serve fragments on this device",click={run(AppleMusicTermuxScripts.Task.START_BRIDGE)})
            divider(box);row(box,"Use local connection","Verify the local helper and select it for ALAC",click={work("Checking local connection…",{termux.useLocal()}){"Local connection selected. Enable ALAC to use it."}})
            divider(box);row(box,"Stop streaming helper",click={run(AppleMusicTermuxScripts.Task.STOP)})
        }
        button("Open terminal"){show("terminal")}
    }
    private fun terminal() {
        title("Terminal")
        group("Audio tools") {box->
            row(box,"Open terminal",click={openEmbeddedTerminal()})
            divider(box);row(box,"Download from a link","Song, album or playlist",click={edit("Apple Music link","https://music.apple.com/…","",false){url->openEmbeddedTerminal(url)}})
            divider(box);row(box,"Check connection",click={work("Checking wrapper…",{embedded.test()}){"Wrapper-lite connected"}})
            divider(box);row(box,"Connection activity",click={show("activity")})
        }
    }
    private fun activity() {
        title("Connection activity")
        group("Activity") {box->row(box,"Clear output",click={AppleMusicTermuxLog.clear(this);console?.text="No connection activity yet."})}
        body.addView(text("Latest connections and streaming events · Newest first",14f,muted).apply {setPadding(dp(4),dp(16),dp(4),dp(12))})
        val output=text(AppleMusicTermuxLog.text(this).ifBlank {"No connection activity yet."},12f,muted).apply {
            typeface=android.graphics.Typeface.MONOSPACE;setPadding(dp(4),0,dp(4),dp(16));setTextIsSelectable(true)
            layoutParams=LinearLayout.LayoutParams(-1,-2)
        };console=output;body.addView(output)
    }
    private fun edit(title: String,hint: String,value: String,secret: Boolean,save: (String)->Unit) {
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),0)}
        content.addView(text(title,22f,strong=true))
        val input=EditText(this).apply {setText(value);this.hint=hint;setTextColor(ink);setHintTextColor(muted);typeface=regular;textSize=17f
            background=shape(card);setPadding(dp(14),dp(14),dp(14),dp(14));setSingleLine(true)
            inputType=if(secret)129 else 17;typeface=regular
            layoutParams=LinearLayout.LayoutParams(-1,dp(56)).apply {topMargin=dp(20);bottomMargin=dp(12)}
        };content.addView(input)
        val dialog=android.app.Dialog(this)
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;background=shape(if(Color.luminance(bg)<.5)0xff111113.toInt() else card,28)}
        panel.addView(content)
        fun action(name: String,strong: Boolean,click: ()->Unit) {
            panel.addView(View(this).apply {setBackgroundColor(ink and 0x00ffffff or 0x18000000)},LinearLayout.LayoutParams(-1,dp(1)))
            panel.addView(text(name,17f,ink,strong).apply {gravity=Gravity.CENTER;background=AppleMusicAppearance(this@AppleMusicAlacActivity).touch(Color.TRANSPARENT,0);setPadding(dp(16),dp(18),dp(16),dp(18));setOnClickListener {click()}})
        }
        action("Save",true){save(input.text.toString());dialog.dismiss()}
        action("Cancel",false){dialog.dismiss()}
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels-dp(48),-2)
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    }
    private fun run(task: AppleMusicTermuxScripts.Task) {runCatching {termux.run(task)}.onSuccess {show("terminal")}.onFailure {status.text=it.message;AppleMusicTermuxLog.append(this,it.message?:"Could not run task")}}
    private fun openTermux(){runCatching {startActivity(packageManager.getLaunchIntentForPackage("com.termux") ?: error("Install Termux first"))}.onFailure {status.text=it.message}}
    private fun link(url: String){runCatching {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure {status.text="No browser is available"}}
    private fun <T> work(message: String,job: ()->T,done: (T)->String) {
        if(busy)return;busy=true;status.text=message
        worker.execute {val result=runCatching(job);runOnUiThread {if(isDestroyed || isFinishing)return@runOnUiThread;busy=false;status.text=result.fold(done){it.message ?: "Connection failed"}}}
    }
    @Deprecated("Activity result for framework-only plugin") override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        if(request==52 && result==RESULT_OK){val uri=data?.data ?: return
            runCatching {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
            work("Exporting audio downloads…",{embedded.exportDownloads(uri)}){"Exported $it files. Existing files were kept."}
        }
        if(request==53 && result==RESULT_OK){val uri=data?.data ?: return
            work("Importing guest files…",{
                val file=java.io.File(cacheDir,"qemu-import.zip")
                try {
                    contentResolver.openInputStream(uri).use {input->requireNotNull(input);file.outputStream().use {output->
                        val buffer=ByteArray(65536);var total=0L
                        while(true){val n=input.read(buffer);if(n<0)break;total+=n;check(total<=128L*1024*1024){"Guest archive is too large"};output.write(buffer,0,n)}
                    }}
                    AppleMusicQemuRuntime(this).install(file) {message->runOnUiThread{if(!isDestroyed){status.text=message;qemuProgress?.text=message}}}
                } finally {file.delete()}
            }){show("qemu",remember=false);"Guest files ready"}
        }
        if(request==51 && result==RESULT_OK){val uri=data?.data ?: return
            work("Preparing wrapper package…",{
                val file=java.io.File(cacheDir,"wrapper-import.zip")
                try {contentResolver.openInputStream(uri).use {input->requireNotNull(input);file.outputStream().use {output->val bytes=ByteArray(65536);var size=0L;while(true){val n=input.read(bytes);if(n<0)break;size+=n;check(size<=512L*1024*1024){"Wrapper ZIP is too large"};output.write(bytes,0,n)}}}}catch(failure: Exception){file.delete();throw failure}
            }){run(AppleMusicTermuxScripts.Task.IMPORT);"Wrapper installation sent to Termux"}
        }
    }
    override fun onRequestPermissionsResult(request: Int,permissions: Array<out String>,results: IntArray){super.onRequestPermissionsResult(request,permissions,results);if(request==41)show(if(page=="qemu")"qemu" else "setup",remember=false)}
    private fun back(){if(pageHistory.isNotEmpty())show(pageHistory.removeLast(),remember=false) else finish()}
    @Deprecated("Legacy Android") override fun onBackPressed(){back()}
    override fun onResume(){super.onResume();resumed=true;if(page=="qemu")show("qemu",remember=false);main.post(refresh)}
    override fun onPause(){resumed=false;main.removeCallbacks(refresh);super.onPause()}
    override fun onDestroy(){main.removeCallbacks(refresh);worker.shutdownNow();super.onDestroy()}
}
