package com.music.streamx.extensions.applemusic

import android.app.*
import android.content.*
import android.os.*
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Native programs are packaged in the APK; the download contains guest data only. */
internal class AppleMusicQemuRuntime(private val context: Context) {
    val home get()=File(context.filesDir,"qemu-wrapper")
    val native get()=File(context.applicationInfo.nativeLibraryDir)
    val executable get()=File(native,"libwrapper_exec.so")
    val available get()=executable.canExecute() && File(native,"libqemu_exec.so").canExecute()
    val installed get()=listOf("vmlinuz-lite-qemu","lite-initramfs.cpio.gz","data.img").all{File(home,it).isFile}
    fun install(source: File? = null,progress: (String)->Unit) {
        check(available){"Embedded QEMU is available in the Android arm64 plugin APK"}
        if(!installed) {
            val zip=File(context.cacheDir,"qemu-guest.zip")
            val staging=File(context.filesDir,"qemu-wrapper-install")
            staging.deleteRecursively();staging.mkdirs()
            try {
                progress(if(source!=null) "Verifying imported guest files…" else "Downloading guest files (83 MB)…")
                val digest=MessageDigest.getInstance("SHA-256")
                if(source!=null) {
                    check(source.length() in 1..128L*1024*1024){"Guest archive exceeds download limit"}
                    source.inputStream().use {input->zip.outputStream().use{output->input.copyTo(output)}}
                    zip.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
                } else {
                    val connection=URL(AppleMusicTermuxScripts.QEMU_ARTIFACT).openConnection() as HttpURLConnection
                    connection.connectTimeout=30000;connection.readTimeout=60000
                    try {
                        check(connection.responseCode==200){"Guest download unavailable (${connection.responseCode}). The upstream build may have expired."}
                        connection.inputStream.use {input->zip.outputStream().use {output->
                            val buffer=ByteArray(65536);var total=0L;var report=0L
                            while(true) {
                                check(!Thread.currentThread().isInterrupted){"Installation cancelled"}
                                val count=input.read(buffer);if(count<0)break
                                total+=count;check(total<=128L*1024*1024){"Guest archive exceeds download limit"}
                                output.write(buffer,0,count);digest.update(buffer,0,count)
                                if(total-report>=4*1024*1024){progress("Downloading guest files · ${total/1048576} MB");report=total}
                            }
                        }}
                    } finally {connection.disconnect()}
                }
                val actual=digest.digest().joinToString(""){"%02x".format(it)}
                check(actual==AppleMusicTermuxScripts.QEMU_SHA256){"Guest checksum mismatch; nothing installed"}
                progress("Preparing guest disk…")
                AppleMusicQemuGuest.extract(zip,staging)
                check(!home.exists() || home.listFiles().isNullOrEmpty()){"Existing guest data was preserved; remove the incomplete installation manually"}
                home.delete();check(staging.renameTo(home)){"Could not install guest files"}
            } finally {zip.delete();staging.deleteRecursively()}
        }
        prepareFirmware()
        progress("Embedded QEMU ready. Sign in to Apple Music, then start the wrapper.")
    }
    fun prepareFirmware() {
        check(installed){"Download or import the guest files first"}
        val target=File(home,"firmware").apply{mkdirs()}
        for(name in context.assets.list("qemu-firmware").orEmpty()) {
            context.assets.open("qemu-firmware/$name").use {input->File(target,name).outputStream().use{input.copyTo(it)}}
        }
        File(home,".lite-qemu-args").delete()
    }
    fun environment()=arrayOf("HOME=${home.absolutePath}","TMPDIR=${context.cacheDir.absolutePath}",
        "PATH=/system/bin:/system/xbin","TERM=xterm-256color","LANG=en_US.UTF-8",
        "LD_LIBRARY_PATH=${native.absolutePath}","STREAMX_QEMU_ASSETS=${home.absolutePath}",
        "QEMU_BIN=${File(native,"libqemu_exec.so").absolutePath}","QEMU_AUDIO_DRV=none")
    fun command(login: Boolean)=AppleMusicQemuCommand.script(executable.absolutePath,login)

}

/** A persistent PTY: leaving the terminal does not terminate the guest. */
class AppleMusicQemuService: Service() {
    internal var session: TerminalSession?=null
    internal var listener: TerminalSessionClient?=null
    private var wake: PowerManager.WakeLock?=null
    private val setupWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var replacingSession=false
    internal var connectionVerified=false
        private set
    private var accountChangePending=false
    private var guided=false
    private var loginMode=false
    private var loginAttempt=0L
    private var loginSucceeded=false
    private var pendingAccount: String?=null
    private var pendingPassword: String?=null
    private var lastCodePrompt=-1
    private var verifying=false
    @Volatile private var closed=false
    private fun state(stage: AppleMusicWrapperSetup.Stage, message: String) {
        AppleMusicWrapperSetup.state=AppleMusicWrapperSetup.State(stage,message)
        getSystemService(NotificationManager::class.java).notify(87,Notification.Builder(this,"apple_qemu")
            .setSmallIcon(R.drawable.ic_music).setContentTitle("Apple Music on this device")
            .setContentText(message).setOngoing(stage!=AppleMusicWrapperSetup.Stage.ERROR)
            .setContentIntent(PendingIntent.getActivity(this,88,Intent(this,AppleMusicWrapperSetupActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null,"Stop",PendingIntent.getService(this,87,Intent(this,javaClass).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)).build())
            .build())
    }
    private fun failSetup(message: String) {
        pendingAccount=null;pendingPassword=null;verifying=false;connectionVerified=false
        state(AppleMusicWrapperSetup.Stage.ERROR,message)
    }
    private fun verifyConnection() {
        if(verifying || closed)return
        verifying=true
        state(AppleMusicWrapperSetup.Stage.STARTING,"Step 2 of 2 · Starting with your saved sign-in…")
        setupWorker.execute {
            val runtime=AppleMusicEmbeddedRuntime(this)
            val deadline=SystemClock.elapsedRealtime()+180000L
            var ready=false
            var signInRequired=false
            while(!closed && !ready && SystemClock.elapsedRealtime()<deadline) {
                try {
                    runtime.test(AppleMusicWrapperAddress("http://127.0.0.1:12340","",""),requireAccount=true)
                    ready=true
                } catch (_: AppleMusicWrapperSetup.SignInRequired) {
                    signInRequired=true
                    break
                } catch (_: Exception) { /* The guest may still be booting. */ }
                if(!ready)Thread.sleep(1000)
            }
            if(!closed) {
                if(signInRequired)main.post { if(!closed) {
                    verifying=false
                    state(AppleMusicWrapperSetup.Stage.SIGN_IN,"Step 1 of 2 · Sign in to Apple Music")
                } }
                else if(ready)runCatching {
                    if(!runtime.ready)runtime.install()
                    runtime.save("http://127.0.0.1:12340","","")
                    runtime.select();AppleMusicAlac(this).setEnabled(true)
                }.onSuccess { main.post { if(!closed) {verifying=false;connectionVerified=true;accountChangePending=false;state(AppleMusicWrapperSetup.Stage.READY,"Ready · Signed in and lossless connection verified")} } }
                    .onFailure {main.post {if(!closed)failSetup("Signed in, but the lossless connection could not start. Try connecting again.")}}
                else main.post {if(!closed) {verifying=false;state(AppleMusicWrapperSetup.Stage.ERROR,"The saved sign-in could not start. Sign in again or retry the connection.")}}
            }
        }
    }
    internal fun cancelAccountChange() {
        if(accountChangePending && AppleMusicWrapperSetup.state.stage==AppleMusicWrapperSetup.Stage.SIGN_IN) {
            accountChangePending=false
            state(if(connectionVerified)AppleMusicWrapperSetup.Stage.READY else AppleMusicWrapperSetup.Stage.IDLE,
                if(connectionVerified)"Ready · Signed in and lossless connection verified" else "Not connected")
        }
    }
    internal fun signIn(account: String,password: String) {
        require(account.length in 1..256 && password.length in 1..4096 && account.isNotBlank() && account.none{it.isISOControl()} && ':' !in account && password.isNotBlank() && password.none{it.isISOControl()}) {"Enter your Apple Account and password"}
        connectionVerified=false;accountChangePending=false
        AppleMusicEmbeddedRuntime(this).stop()
        guided=true;pendingAccount=account.trim();pendingPassword=password
        loginMode=true;loginSucceeded=false;lastCodePrompt=-1
        state(AppleMusicWrapperSetup.Stage.SIGNING_IN,"Step 1 of 2 · Signing in to Apple Music…")
        val attempt=++loginAttempt
        main.postDelayed({
            if(!closed && loginAttempt==attempt && loginMode && AppleMusicWrapperSetup.state.stage in listOf(AppleMusicWrapperSetup.Stage.SIGNING_IN,AppleMusicWrapperSetup.Stage.TWO_FACTOR))
                failSetup("Sign-in timed out. Check your internet connection and try again.")
        },300000)
        val old=session
        if(old?.isRunning==true) {
            replacingSession=true
            runCatching {if(old.pid>0)android.system.Os.kill(-old.pid,android.system.OsConstants.SIGTERM)}
            old.finishIfRunning()
            val deadline=SystemClock.elapsedRealtime()+5000
            fun launchWhenStopped() {
                if(closed)return
                if(old.isRunning && SystemClock.elapsedRealtime()<deadline)main.postDelayed({launchWhenStopped()},100)
                else {replacingSession=false;if(old.isRunning)failSetup("The previous connection is still stopping. Try again shortly.") else launchSession(true)}
            }
            launchWhenStopped()
        } else launchSession(true)
    }
    internal fun submitCode(code: String) {
        require(AppleMusicWrapperSetup.validCode(code)){"Enter the six-digit verification code"}
        check(AppleMusicWrapperSetup.state.stage==AppleMusicWrapperSetup.Stage.TWO_FACTOR)
        state(AppleMusicWrapperSetup.Stage.SIGNING_IN,"Step 1 of 2 · Verifying your code…")
        session?.write(code+"\n")
    }
    private fun inspectLogin(active: TerminalSession) {
        if(!guided || !loginMode || AppleMusicWrapperSetup.state.stage==AppleMusicWrapperSetup.Stage.ERROR)return
        val output=active.emulator?.screen?.transcriptText.orEmpty()
        if(AppleMusicWrapperSetup.awaitingAccount(output))pendingAccount?.let {pendingAccount=null;active.write(it+"\n")}
        if(AppleMusicWrapperSetup.awaitingPassword(output))pendingPassword?.let {pendingPassword=null;active.write(it+"\n")}
        val codeAt=output.lastIndexOf("2FA code:")
        if(codeAt>lastCodePrompt) {lastCodePrompt=codeAt;state(AppleMusicWrapperSetup.Stage.TWO_FACTOR,"Enter the verification code sent by Apple")}
        if(AppleMusicWrapperSetup.loginComplete(output))loginSucceeded=true
        if(output.contains("login failed",true) || output.contains("2FA code timeout",true) || output.contains("failed to cache account info",true))
            failSetup("Apple Music sign-in failed. Check your account, password and verification code, then try again.")
    }
    private fun launchSession(login: Boolean) {
        val runtime=AppleMusicQemuRuntime(this);check(runtime.available && runtime.installed){"Install local wrapper files first"}
        runtime.prepareFirmware()
        if(wake?.isHeld!=true)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AppleMusic:Qemu").apply {acquire(6*60*60*1000L)}
        session=TerminalSession("/system/bin/sh",runtime.home.absolutePath,arrayOf("/system/bin/sh","-c",runtime.command(login)),runtime.environment(),2000,client).also {it.updateSize(100,30,10,20)}
    }
    override fun onCreate() {
        super.onCreate();current=this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("apple_qemu","Local Apple Music wrapper",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,87,Intent(this,javaClass).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,88,Intent(this,AppleMusicTerminalActivity::class.java).putExtra("qemu",true),PendingIntent.FLAG_IMMUTABLE)
        startForeground(87,Notification.Builder(this,"apple_qemu").setSmallIcon(R.drawable.ic_music).setContentTitle("Local Apple Music wrapper")
            .setContentText("QEMU runs inside the Apple Music plugin").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Stop",stop).build()).build())
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
        if(intent?.action in setOf("setup","change_account")) {
            val changeAccount=intent?.action=="change_account"
            if(!changeAccount && accountChangePending && AppleMusicWrapperSetup.state.stage==AppleMusicWrapperSetup.Stage.SIGN_IN)return START_NOT_STICKY
            if(changeAccount)accountChangePending=true
            guided=true
            if(verifying || AppleMusicWrapperSetup.state.stage in listOf(AppleMusicWrapperSetup.Stage.PREPARING,AppleMusicWrapperSetup.Stage.SIGNING_IN,AppleMusicWrapperSetup.Stage.TWO_FACTOR))return START_NOT_STICKY
            state(AppleMusicWrapperSetup.Stage.PREPARING,"Preparing local lossless playback…")
            setupWorker.execute {
                try {
                    val runtime=AppleMusicQemuRuntime(this);val existing=runtime.installed
                    runtime.install {message->main.post {if(!closed)state(AppleMusicWrapperSetup.Stage.PREPARING,message)}}
                    main.post {if(!closed) {
                        if(changeAccount)state(AppleMusicWrapperSetup.Stage.SIGN_IN,"Sign in with your new Apple Account")
                        else if(existing) {loginMode=false;if(session?.isRunning!=true)launchSession(false);verifyConnection()}
                        else state(AppleMusicWrapperSetup.Stage.SIGN_IN,"Step 1 of 2 · Sign in to Apple Music")
                    }}
                } catch(_: Exception){main.post {if(!closed)failSetup("Could not prepare local playback. Check your internet connection and try again.")}}
            }
        } else try {
            if(session?.isRunning!=true)launchSession(intent?.getBooleanExtra("login",false)==true)
        } catch(_: Exception){failSetup("Local playback could not start. Try setup again.")}
        return START_NOT_STICKY
    }
    private val client=object: TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession){inspectLogin(session);listener?.onTextChanged(session)}
        override fun onTitleChanged(session: TerminalSession){}
        override fun onSessionFinished(session: TerminalSession){
            if(replacingSession)return
            inspectLogin(session);listener?.onSessionFinished(session)
            File(AppleMusicQemuRuntime(this@AppleMusicQemuService).home,".lite-qemu-args").delete()
            if(guided && loginMode && loginSucceeded && session.exitStatus==0 && !closed) {
                loginMode=false
                this@AppleMusicQemuService.session=null
                launchSession(false);verifyConnection()
            } else if(guided) {
                this@AppleMusicQemuService.session=null
                wake?.let{if(it.isHeld)it.release()}
                if(AppleMusicWrapperSetup.state.stage!=AppleMusicWrapperSetup.Stage.ERROR)
                    failSetup("Local playback stopped before it was ready. Sign in again or retry the connection.")
            } else {wake?.let{if(it.isHeld)it.release()};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        }
        override fun onCopyTextToClipboard(session: TerminalSession,text: String){listener?.onCopyTextToClipboard(session,text)}
        override fun onPasteTextFromClipboard(session: TerminalSession?){listener?.onPasteTextFromClipboard(session)}
        override fun onBell(session: TerminalSession){}
        override fun onColorsChanged(session: TerminalSession){listener?.onColorsChanged(session)}
        override fun onTerminalCursorStateChange(state: Boolean){}
        override fun setTerminalShellPid(session: TerminalSession,pid: Int){}
        override fun getTerminalCursorStyle()=0
        override fun logError(tag: String,message: String){}
        override fun logWarn(tag: String,message: String){}
        override fun logInfo(tag: String,message: String){}
        override fun logDebug(tag: String,message: String){}
        override fun logVerbose(tag: String,message: String){}
        override fun logStackTraceWithMessage(tag: String,message: String,e: Exception){}
        override fun logStackTrace(tag: String,e: Exception){}
    }
    override fun onBind(intent: Intent?)=null
    override fun onDestroy() {
        closed=true;pendingAccount=null;pendingPassword=null
        main.removeCallbacksAndMessages(null);setupWorker.shutdownNow()
        AppleMusicWrapperSetup.state=AppleMusicWrapperSetup.State(AppleMusicWrapperSetup.Stage.IDLE,"Not connected")
        listener=null
        session?.let {runCatching {if(it.pid>0)android.system.Os.kill(-it.pid,android.system.OsConstants.SIGTERM)};it.finishIfRunning()}
        session=null;wake?.let{if(it.isHeld)it.release()};wake=null
        File(AppleMusicQemuRuntime(this).home,".lite-qemu-args").delete()
        if(current===this)current=null
        super.onDestroy()
    }
    companion object {@Volatile internal var current: AppleMusicQemuService?=null}
}

/** Only known guest data entries can be installed, with a hard expanded-size bound. */
internal object AppleMusicQemuGuest {
    val names=listOf("vmlinuz-lite-qemu","lite-initramfs.cpio.gz","data.img")
    fun extract(zip: File,target: File) {
        ZipFile(zip).use {archive->
            for(name in names) {
                val entry=archive.getEntry("qemu/$name") ?: error("Guest archive missing $name")
                check(entry.size in 1..(1024L*1024*1024)){"Invalid guest file size"}
                archive.getInputStream(entry).use {input->File(target,name).outputStream().use {output->
                    val buffer=ByteArray(65536);var copied=0L
                    while(true){val n=input.read(buffer);if(n<0)break;copied+=n;check(copied<=entry.size){"Guest size exceeded"};output.write(buffer,0,n)}
                    check(copied==entry.size){"Incomplete guest file"}
                }}
            }
        }
    }
}

internal object AppleMusicQemuCommand {
    fun script(executable: String,login: Boolean): String {
        val base=AppleMusicTermuxScripts.quote(executable)+" --accel tcg --host 127.0.0.1 --host-port 12340"
        return if(!login)"exec $base" else """
            umask 077
            SX_TTY=${'$'}(stty -g) || exit 1
            trap 'stty "${'$'}SX_TTY"; unset SX_ACCOUNT SX_SECRET; rm -f .lite-qemu-args' EXIT
            printf 'Apple Account: '; IFS= read -r SX_ACCOUNT || exit 1
            stty -echo || exit 1
            printf 'Password (hidden): '
            IFS= read -r SX_SECRET || exit 1
            stty "${'$'}SX_TTY"; printf '\n'
            [ -n "${'$'}SX_ACCOUNT" ] && [ -n "${'$'}SX_SECRET" ] || exit 1
            $base --login "${'$'}SX_ACCOUNT:${'$'}SX_SECRET"
        """.trimIndent()
    }
}
