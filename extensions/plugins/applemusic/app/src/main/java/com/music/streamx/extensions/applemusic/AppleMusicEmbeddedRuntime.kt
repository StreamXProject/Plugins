package com.music.streamx.extensions.applemusic

import android.app.*
import android.content.*
import android.os.*
import android.provider.DocumentsContract as Documents
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No credentials are passed in shell arguments, Android intents or notifications. */
internal data class AppleMusicWrapperAddress(val address: String, val username: String, val password: String) {
    val authenticated: String get() {
        val uri = URI(address)
        return URI(uri.scheme, if(username.isNotBlank()) "$username:$password" else null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    }
    override fun toString() = "Wrapper(address=$address, credentials=[redacted])"
    companion object {
        fun parse(raw: String, username: String = "", password: String = ""): AppleMusicWrapperAddress {
            val uri = runCatching { URI(raw.trim()) }.getOrNull()
            require(uri != null && uri.host != null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.scheme == "https" || uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1", "[::1]"))) {
                "Enter an HTTPS wrapper-lite address (HTTP is allowed for localhost)"
            }
            val auth = uri.rawUserInfo?.split(':', limit = 2)
            fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            val user = if(auth != null) decode(auth[0]) else username.trim()
            val secret = if(auth != null) decode(auth.getOrElse(1) { "" }) else password
            require(':' !in user && user.length <= 128 && secret.length <= 2048 && (user + secret).none { it.isISOControl() }) { "Invalid wrapper credentials" }
            val clean = URI(uri.scheme, null, uri.host, uri.port, uri.path.trimEnd('/'), null, null).toASCIIString()
            return AppleMusicWrapperAddress(clean, user, secret)
        }
    }
}

internal class AppleMusicEmbeddedRuntime(val context: Context) {
    private val prefs get() = context.getSharedPreferences("apple_music_session", 0)
    val home get() = File(context.filesDir, "embedded-tools").apply { mkdirs() }
    val runner get() = File(context.applicationInfo.nativeLibraryDir, "libstreamx_alac_exec.so")
    val downloader get() = File(context.applicationInfo.nativeLibraryDir, "libamdl_exec.so")
    val available get() = runner.canExecute() && downloader.canExecute()
    val ready get() = available && prefs.getBoolean("alac_embedded_ready", false)
    val wrapper: AppleMusicWrapperAddress get() = AppleMusicWrapperAddress(
        prefs.getString("alac_wrapper_url", "").orEmpty(), prefs.getString("alac_wrapper_user", "amdl").orEmpty(), prefs.getString("alac_wrapper_password", "").orEmpty())
    val configured get() = wrapper.address.isNotBlank()
    val localToken: String get() = synchronized(lock) {
        prefs.getString("alac_embedded_token", null) ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("alac_embedded_token", it).commit() }
    }
    fun save(raw: String, user: String, password: String) {
        val value = AppleMusicWrapperAddress.parse(raw, user, password)
        prefs.edit().putString("alac_wrapper_url", value.address).putString("alac_wrapper_user", value.username)
            .putString("alac_wrapper_password", value.password).commit()
        if(ready) prepare()
    }
    fun test(value: AppleMusicWrapperAddress = wrapper, requireAccount: Boolean = false) {
        val parsed = AppleMusicWrapperAddress.parse(value.address, value.username, value.password)
        val connection = URL(parsed.address + "/status").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 45000; connection.instanceFollowRedirects = false
        if(parsed.username.isNotBlank()) connection.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString(
            (parsed.username + ":" + parsed.password).toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
        try {
            check(connection.responseCode == 200) { if(connection.responseCode in listOf(401,403)) "Wrapper rejected the username or access token" else "Wrapper unavailable (${connection.responseCode})" }
            val data = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(2048)
                while(output.size() <= 65536) { val count = input.read(buffer, 0, minOf(buffer.size, 65537-output.size())); if(count < 0) break; output.write(buffer, 0, count) }
                output.toByteArray()
            }
            check(data.size <= 65536) { "Invalid wrapper response" }
            val status=JSONObject(String(data, Charsets.UTF_8))
            check(status.optInt("code", -1) == 0) { "This address did not return a wrapper-lite status" }
            if(requireAccount)AppleMusicWrapperSetup.requireSavedAccount(status)
        } finally { connection.disconnect() }
    }
    fun lyrics(id: String): String? {
        require(id.matches(Regex("[0-9]{1,30}")))
        if(!configured || wrapper.address=="http://127.0.0.1:12340" && AppleMusicQemuService.current?.session?.isRunning!=true)return null
        val value=wrapper
        val connection=URL(value.address+"/lyrics?adamId=$id&language=en&syllable=1").openConnection() as HttpURLConnection
        connection.connectTimeout=5000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
        if(value.username.isNotBlank())connection.setRequestProperty("Authorization","Basic "+android.util.Base64.encodeToString((value.username+":"+value.password).toByteArray(),android.util.Base64.NO_WRAP))
        try {
            if(connection.responseCode!=200)return null
            val raw=connection.inputStream.use {input->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;check(output.size()+n<=256000);output.write(buffer,0,n)}
                output.toString("UTF-8")
            }
            return AppleMusicLyrics.document(JSONObject(raw))
        } finally {connection.disconnect()}
    }
    fun install() {
        check(available) { "This APK has no embedded audio runtime for this device" }
        File(home, "tmp").mkdirs()
        prefs.edit().putBoolean("alac_embedded_ready", true).commit()
        prepare()
        AppleMusicTermuxLog.append(context, "Embedded terminal and audio tools ready. No separate Termux app required.")
    }
    fun prepare(cleanCache: Boolean = false): File = synchronized(lock) {
        check(available) { "Embedded runtime unavailable for this device" }
        val token = AppleMusicSession(context).userToken
        val wrapperUrl = if(configured) AppleMusicWrapperAddress.parse(wrapper.address, wrapper.username, wrapper.password).authenticated else ""
        val downloads = File(home, "downloads").apply { mkdirs() }
        val cache = File(context.cacheDir, "embedded-alac").apply { mkdirs() }
        if(cleanCache)cache.listFiles()?.filter { it.name.startsWith("alac-") }?.forEach { it.deleteRecursively() }
        val config = JSONObject().put("wrapper", wrapperUrl).put("token", localToken).put("listen", "127.0.0.1:12441").put("cache", cache.absolutePath)
        privateWrite(File(home, "runtime.json"), config.toString())
        val yaml = buildString {
            append("# Managed by Apple Music settings. Credentials remain private.\ngeneral:\n")
            append("  lite-server: ${JSONObject.quote(wrapperUrl)}\n  media-user-token: ${JSONObject.quote(token)}\npaths:\n")
            for((kind, folder) in listOf("alac" to "alac", "aac" to "aac", "atmos" to "atmos", "mv" to "mv"))
                append("  $kind: ${JSONObject.quote(File(downloads, folder).absolutePath)}\n")
        }
        privateWrite(File(home, "config.yaml"), yaml)
        // The system shell handles interactive input; executables live in Android's
        // read-only APK native directory. No writable-directory exec workaround.
        privateWrite(File(home, "shell.rc"), "alias amdl=${AppleMusicTermuxScripts.quote(downloader.absolutePath)}\nPS1='apple-music $ '\n")
        File(home, "runtime.json")
    }
    /** Restore the saved local wrapper after Android has reclaimed the plugin. */
    fun ensureLocalWrapper() {
        if(wrapper.address!="http://127.0.0.1:12340")return
        check(AppleMusicQemuRuntime(context).installed){"Set up local playback in Apple Music settings first"}
        check(Looper.myLooper()!=Looper.getMainLooper())
        val service=AppleMusicQemuService.current
        if(service?.connectionVerified==true && service.session?.isRunning==true)return
        if(service!=null && AppleMusicWrapperSetup.state.stage in listOf(AppleMusicWrapperSetup.Stage.SIGN_IN,AppleMusicWrapperSetup.Stage.TWO_FACTOR))error("Complete Apple Music sign-in under Local playback")
        val previous=AppleMusicWrapperSetup.state
        context.startForegroundService(Intent(context,AppleMusicQemuService::class.java).setAction("setup"))
        val deadline=SystemClock.elapsedRealtime()+210000L
        while(SystemClock.elapsedRealtime()<deadline) {
            val state=AppleMusicWrapperSetup.state
            if(state===previous) {Thread.sleep(200);continue}
            when(state.stage) {
                AppleMusicWrapperSetup.Stage.READY -> return
                AppleMusicWrapperSetup.Stage.ERROR -> error(state.message)
                AppleMusicWrapperSetup.Stage.SIGN_IN,AppleMusicWrapperSetup.Stage.TWO_FACTOR -> error("Complete Apple Music sign-in under Local playback")
                else -> Thread.sleep(200)
            }
        }
        error("Local playback did not become ready. Check Apple Music settings.")
    }
    fun ensure() {
        check(ready && configured) { "Set up the embedded runtime and wrapper connection first" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Start the runtime in the background" }
        val done = CountDownLatch(1)
        var failure: String? = "Embedded streaming startup timed out"
        val receiver = object: ResultReceiver(null) {
            override fun onReceiveResult(code: Int, data: Bundle?) { failure = if(code == 0) null else "Embedded streaming could not start. Check terminal activity."; done.countDown() }
        }
        context.startForegroundService(Intent(context, AppleMusicEmbeddedService::class.java).putExtra("ready", receiver))
        check(done.await(15, TimeUnit.SECONDS)) { "Embedded streaming startup timed out" }
        check(failure == null) { failure.orEmpty() }
    }
    fun select() {
        ensure()
        AppleMusicAlac(context).selectEmbedded(localToken)
    }
    fun stop() { context.stopService(Intent(context, AppleMusicEmbeddedService::class.java)) }
    fun exportDownloads(tree: android.net.Uri): Int {
        val resolver=context.contentResolver
        val root=Documents.buildDocumentUriUsingTree(tree,Documents.getTreeDocumentId(tree))
        val source=File(home,"downloads").canonicalFile
        fun children(parent: android.net.Uri): Map<String,String> {
            val result=mutableMapOf<String,String>()
            val uri=Documents.buildChildDocumentsUriUsingTree(tree,Documents.getDocumentId(parent))
            resolver.query(uri,arrayOf(Documents.Document.COLUMN_DOCUMENT_ID,Documents.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {cursor->
                while(cursor.moveToNext())result[cursor.getString(1)]=cursor.getString(0)
            }
            return result
        }
        var exported=0
        fun copy(directory: File,parent: android.net.Uri) {
            val existing=children(parent)
            for(file in directory.listFiles().orEmpty()) {
                if(!file.canonicalPath.startsWith(source.path+File.separator))continue
                if(file.isDirectory) {
                    val id=existing[file.name]
                    val target=if(id!=null)Documents.buildDocumentUriUsingTree(tree,id) else Documents.createDocument(resolver,parent,Documents.Document.MIME_TYPE_DIR,file.name)
                    if(target!=null)copy(file,target)
                } else if(file.extension.lowercase() in listOf("m4a","flac","aac","wav","opus","mp4") && file.name !in existing) {
                    val target=Documents.createDocument(resolver,parent,if(file.extension=="flac")"audio/flac" else "audio/mp4",file.name) ?: continue
                    try {resolver.openOutputStream(target,"w").use {output->requireNotNull(output);file.inputStream().use {it.copyTo(output,65536)}};exported++}
                    catch(failure: Exception){runCatching {Documents.deleteDocument(resolver,target)};throw failure}
                }
            }
        }
        copy(source,root)
        return exported
    }
    fun signature() = java.security.MessageDigest.getInstance("SHA-256").digest((wrapper.authenticated + localToken).toByteArray()).joinToString(""){"%02x".format(it)}
    private fun privateWrite(file: File, content: String) {
        val temporary = File(file.parentFile, file.name + ".new")
        temporary.writeText(content)
        android.system.Os.chmod(temporary.absolutePath, 384)
        check(temporary.renameTo(file)) { "Could not save private runtime configuration" }
    }
    companion object { private val lock = Any() }
}

class AppleMusicEmbeddedService: Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var process: java.lang.Process? = null
    private var signature: String? = null
    @Volatile private var closed = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("apple_embedded", "Lossless streaming", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 86, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(86, Notification.Builder(this, "apple_embedded").setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Apple Music lossless streaming").setContentText("Audio tools running on this device")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val receiver = if(Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("ready", ResultReceiver::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<ResultReceiver>("ready")
        worker.execute {
            try {
                check(!closed)
                val runtime = AppleMusicEmbeddedRuntime(this)
                val expected = runtime.signature()
                if(process?.isAlive != true || signature != expected) {
                    process?.destroy(); process?.waitFor(2, TimeUnit.SECONDS); process?.destroyForcibly(); process?.waitFor(1, TimeUnit.SECONDS)
                    val file = runtime.prepare(cleanCache = true)
                    val launched = ProcessBuilder(runtime.runner.absolutePath, "--runtime-config", file.absolutePath)
                        .directory(runtime.home).redirectErrorStream(true).start()
                    synchronized(this) { if(closed) { launched.destroyForcibly(); error("Runtime stopped") }; process = launched }
                    signature = expected
                    Thread {
                        launched.inputStream.bufferedReader().use { reader ->
                            while(true) { val line = reader.readLine() ?: break; AppleMusicTermuxLog.append(applicationContext, line) }
                        }
                        if(!closed) AppleMusicTermuxLog.append(applicationContext, "Embedded streaming helper exited")
                    }.apply { isDaemon = true; name = "AppleLosslessOutput"; start() }
                }
                var healthy = false
                repeat(50) {
                    if(!healthy && !closed && process?.isAlive == true) {
                        healthy = runCatching { AppleMusicAlac(this).test("http://127.0.0.1:12441", runtime.localToken) }.isSuccess
                        if(!healthy) Thread.sleep(100)
                    }
                }
                check(healthy) { "Embedded helper did not become ready" }
                receiver?.send(0, Bundle.EMPTY)
            } catch(_: Exception) {
                AppleMusicTermuxLog.append(this, "Embedded streaming startup failed. Check wrapper settings and runtime installation.")
                receiver?.send(1, Bundle.EMPTY)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        closed = true
        synchronized(this) { process?.destroy(); process?.destroyForcibly(); process = null }
        worker.shutdownNow()
        super.onDestroy()
    }
}
