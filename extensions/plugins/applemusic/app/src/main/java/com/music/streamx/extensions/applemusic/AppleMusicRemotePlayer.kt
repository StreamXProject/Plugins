package com.music.streamx.extensions.applemusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.webkit.*
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Protected audio stays in Apple's WebView player; only transport and real clock state cross IPC. */
internal object AppleMusicRemotePlayer {
    private val main=Handler(Looper.getMainLooper())
    private var browser: WebView?=null
    private var context: Context?=null
    private var owner: IBinder?=null
    private var death: IBinder.DeathRecipient?=null
    private var generation=0L
    private var accountGeneration=0L
    private var track=""
    private var catalogueId=""
    private var desired=false
    private var hasPlayed=false
    private var controlRevision=0L
    private var qualityKbps=AppleMusicQuality.DEFAULT_KBPS
    private var startedAt=0L
    private var script=""
    private var pageError: String?=null
    private var savedStatus=""
    @Volatile private var snapshot=JSONObject().put("state","idle").put("position_ms",0).toString()
    fun status(): String = snapshot
    fun call(app: Context,args: Bundle,playbackId: String?=null): String {
        check(Looper.myLooper()!=Looper.getMainLooper()){ "Playback commands must run in the background" }
        val result=AtomicReference<String>()
        val failure=AtomicReference<Throwable>()
        val done=CountDownLatch(1)
        main.post {
            try {
                val command=args.getString("command","status")
                if(command=="load") load(app,args,requireNotNull(playbackId))
                else {
                    check(owner==null || owner==args.getBinder("owner")){"Playback belongs to another host session"}
                    when(command) {
                        "stop"->destroy()
                        "play","pause","seek"->control(command,args.getLong("position_ms",0))
                        "status"->Unit
                        else->error("Unsupported Apple Music playback command")
                    }
                }
                result.set(snapshot)
            } catch(error: Throwable){failure.set(error)} finally {done.countDown()}
        }
        check(done.await(5,TimeUnit.SECONDS)){"Apple Music player did not respond"}
        failure.get()?.let { throw IllegalStateException(it.message ?: "Apple Music playback failed",it) }
        return result.get() ?: snapshot
    }
    private fun load(app: Context,args: Bundle,id: String) {
        val session=AppleMusicSession(app)
        check(session.connected){"Sign in to Apple Music first"}
        val caller=args.getBinder("owner") ?: error("Missing playback owner")
        check(caller.isBinderAlive){"Playback owner disconnected"}
        val retain=browser!=null && owner==caller && accountGeneration==session.generation
        if(!retain) {
            destroy(stopService=false)
            context=app.applicationContext;owner=caller
            death=IBinder.DeathRecipient {main.post {if(owner==caller)destroy()}}
            caller.linkToDeath(requireNotNull(death),0)
            accountGeneration=session.generation
            script=app.assets.open("playback.js").bufferedReader().use {it.readText()}
        }
        generation++;val loadGeneration=generation
        track=args.getString("track_id").orEmpty();catalogueId=id;desired=args.getBoolean("play",true)
        qualityKbps=AppleMusicQuality.validate(app.getSharedPreferences("apple_music_session",0).getInt("quality_kbps",AppleMusicQuality.DEFAULT_KBPS))
        startedAt=SystemClock.elapsedRealtime();pageError=null;hasPlayed=false
        snapshot=JSONObject().put("track_id",track).put("state",if(desired)"buffering" else "paused").put("position_ms",0).put("duration_ms",0).toString()
        val view=browser ?: WebView(app.applicationContext).also {
            browser=it
            AppleMusicBrowser.configure(it)
            it.settings.mediaPlaybackRequiresUserGesture=false
            it.layout(0,0,app.resources.displayMetrics.widthPixels,app.resources.displayMetrics.heightPixels)
            it.webChromeClient=object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val origin=request.origin
                    if(origin.scheme=="https" && origin.host=="music.apple.com" && (origin.port==-1 || origin.port==443) && PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources)
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    else request.deny()
                }
            }
            it.webViewClient=object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView,request: WebResourceRequest): Boolean = request.isForMainFrame &&
                    !(request.url.scheme=="https" && request.url.host=="music.apple.com")
                override fun onReceivedError(v: WebView,request: WebResourceRequest,error: WebResourceError) {if(request.isForMainFrame)pageError="Apple Music player page failed to load"}
                override fun onReceivedSslError(v: WebView,handler: SslErrorHandler,error: SslError){handler.cancel();pageError="Apple Music player could not establish a secure connection"}
            }
            it.loadUrl("https://music.apple.com/${session.storefront}/home")
        }
        poll(view,loadGeneration)
    }
    private fun poll(view: WebView,loadGeneration: Long) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if(browser!==view || generation!=loadGeneration)return
                val app=context ?: return
                if(AppleMusicSession(app).generation!=accountGeneration) {
                    control("pause",0)
                    snapshot=JSONObject().put("error","Apple Music account changed; start playback again").put("state","error").put("track_id",track).toString()
                    return
                }
                if(Uri.parse(view.url.orEmpty()).host=="music.apple.com") {
                    val source=script.replace("__WANTED__",JSONObject.quote(catalogueId)).replace("__GENERATION__",loadGeneration.toString()).replace("__REQUESTED__",desired.toString()).replace("__QUALITY__",qualityKbps.toString()).replace("__STATION__",track.startsWith("stations:").toString())
                    val revision=controlRevision
                    view.evaluateJavascript(source) { raw ->
                        if(browser!==view || generation!=loadGeneration || controlRevision!=revision)return@evaluateJavascript
                        val value=runCatching{JSONTokener(raw ?: "null").nextValue() as? JSONObject}.getOrNull() ?: JSONObject().put("state","buffering")
                        value.put("track_id",track).put("startup_ms",SystemClock.elapsedRealtime()-startedAt)
                        if(!desired && value.isNull("error"))value.put("state","paused")
                        if(pageError!=null)value.put("error",pageError)
                        if(value.optString("state")=="playing")hasPlayed=true
                        if(desired && !hasPlayed && value.optString("state") !in setOf("playing","ended") && SystemClock.elapsedRealtime()-startedAt>60000)
                            value.put("error","Apple Music could not start this track. Check your subscription and WebView compatibility.")
                        snapshot=value.toString()
                        val status=if(!value.isNull("error"))value.optString("error") else if(value.optString("state")=="paused") "Paused" else value.optString("play_control","Ready")
                        if(status!=savedStatus) {
                            savedStatus=status
                            app.getSharedPreferences("apple_music_session",Context.MODE_PRIVATE).edit().putString("playback_status",status).apply()
                        }
                    }
                } else if(SystemClock.elapsedRealtime()-startedAt>60000) {
                    snapshot=JSONObject().put("track_id",track).put("state","error").put("error","Apple Music requires sign-in. Open your account and reconnect.").toString()
                }
                main.postDelayed(this,250)
            }
        },100)
    }
    private fun control(command: String,position: Long) {
        controlRevision++
        if(command=="play")desired=true
        if(command=="pause" || command=="stop")desired=false
        if(command=="pause")snapshot=runCatching {JSONObject(snapshot).put("state","paused").toString()}.getOrDefault(snapshot)
        browser?.evaluateJavascript("window.__bitChordApplePlayer?.command(${JSONObject.quote(command)},${position.coerceAtLeast(0)})",null)
    }
    fun pause(){main.post {control("pause",0)}}
    fun stop(){main.post {destroy()}}
    // onDestroy runs on the main thread. A replacement service must not receive a queued old teardown.
    fun serviceDestroyed(){ if(Looper.myLooper()==Looper.getMainLooper())destroy(stopService=false) else main.post {destroy(stopService=false)} }
    private fun destroy(stopService: Boolean=true) {
        generation++;control("stop",0)
        browser?.let {it.stopLoading();it.destroy()};browser=null
        owner?.let {value->death?.let {runCatching{value.unlinkToDeath(it,0)}}};owner=null;death=null
        if(stopService)context?.stopService(Intent(context,AppleMusicPlaybackService::class.java));context=null
        track="";catalogueId="";snapshot=JSONObject().put("state","idle").put("position_ms",0).toString()
    }
}
