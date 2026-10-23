package com.music.streamx.extensions.applemusic

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Optional fragment bridge. No Apple credentials or wrapper runtime are bundled. */
internal class AppleMusicAlac(private val context: Context) {
    private val prefs get()=context.getSharedPreferences("apple_music_session",0)
    val enabled get()=prefs.getBoolean("alac_enabled",false)
    val endpoint get()=prefs.getString("alac_endpoint","").orEmpty()
    val token get()=prefs.getString("alac_token","").orEmpty()
    val mode get()=prefs.getString("alac_mode","external").orEmpty()
    val externalEndpoint get()=prefs.getString("alac_external_endpoint",if(mode=="external")endpoint else "").orEmpty()
    val externalToken get()=prefs.getString("alac_external_token",if(mode=="external")token else "").orEmpty()
    fun setMaxRate(value: Int){require(value in listOf(48000,96000,192000));prefs.edit().putInt("alac_max_rate",value).apply()}
    fun saveExternal(url: String,accessToken: String,rate: Int){
        save(url,accessToken,rate)
        prefs.edit().putString("alac_mode","external").putString("alac_external_endpoint",endpoint).putString("alac_external_token",token).apply()
        AppleMusicEmbeddedRuntime(context).stop()
    }
    fun selectLocal(accessToken: String){
        test("http://127.0.0.1:12440",accessToken)
        val oldUrl=externalEndpoint;val oldToken=externalToken
        save("http://127.0.0.1:12440",accessToken,maxRate)
        prefs.edit().putString("alac_mode","local").putString("alac_external_endpoint",oldUrl).putString("alac_external_token",oldToken).apply()
    }
    fun selectEmbedded(accessToken: String) {
        test("http://127.0.0.1:12441",accessToken)
        val oldUrl=externalEndpoint;val oldToken=externalToken
        save("http://127.0.0.1:12441",accessToken,maxRate)
        prefs.edit().putString("alac_mode","embedded").putString("alac_external_endpoint",oldUrl).putString("alac_external_token",oldToken).apply()
    }
    val maxRate get()=prefs.getInt("alac_max_rate",48000)
    fun save(url: String, accessToken: String, rate: Int) {
        val clean=url.trim().trimEnd('/')
        validateEndpoint(clean)
        require(accessToken.length in 24..512 && accessToken.none {it.isWhitespace()}){"Enter the bridge access token (at least 24 characters)"}
        require(rate in listOf(48000,96000,192000)){"Choose a supported sample-rate limit"}
        prefs.edit().putString("alac_endpoint",clean).putString("alac_token",accessToken).putInt("alac_max_rate",rate).apply()
    }
    fun setEnabled(value: Boolean) {
        if(value){validateEndpoint(endpoint);check(token.length>=24){"Configure the ALAC connection first"}}
        prefs.edit().putBoolean("alac_enabled",value).apply()
        if(!value)AppleMusicEmbeddedRuntime(context).stop()
    }
    fun check()=test(endpoint,token)
    fun test(url: String,accessToken: String) { val value=request("/v1/health",url=url,accessToken=accessToken);check(value.optBoolean("ok") && value.optInt("protocol")==1 && value.optString("format")=="alac"){"This address is not a compatible ALAC bridge"} }
    fun stream(id: String): JSONObject {
        check(enabled){"ALAC playback is disabled. Select the track again to use AAC."}
        check(AppleMusicSession(context).connected){"Sign in to Apple Music first"}
        if(mode=="embedded")AppleMusicEmbeddedRuntime(context).let {it.ensureLocalWrapper();it.ensure()}
        val song=AppleMusicPlaybackId.catalogueForAlac(id)
        val result=request("/v1/sessions",JSONObject().put("id",song).put("max_sample_rate",maxRate))
        val path=result.optString("path")
        require(path.matches(Regex("/v1/sessions/[a-f0-9]{48}/index\\.m3u8"))){"The ALAC bridge returned an invalid stream"}
        // The host reads only this provider's scoped URI, never the connection token.
        val uri=Uri.Builder().scheme("content").authority(context.packageName+".source").path("/alac"+path).build()
        return JSONObject().put("url",uri.toString()).put("codec","alac").put("lossless",true)
    }
    private fun connection(path: String,url: String=endpoint,accessToken: String=token): HttpURLConnection {
        validateEndpoint(url)
        return (URL(url.trimEnd('/')+path).openConnection() as HttpURLConnection).apply {
            connectTimeout=15000;readTimeout=95000;instanceFollowRedirects=false
            setRequestProperty("Authorization","Bearer $accessToken")
        }
    }
    private fun request(path: String,body: JSONObject?=null,url: String=endpoint,accessToken: String=token): JSONObject {
        val conn=connection(path,url,accessToken)
        try {
            if(body!=null){conn.requestMethod="POST";conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json");conn.outputStream.use {it.write(body.toString().toByteArray())}}
            check(conn.responseCode in 200..299){when(conn.responseCode){401,403->"ALAC bridge rejected the access token";429->"ALAC bridge is busy. Retry shortly.";else->"ALAC bridge could not prepare this recording (${conn.responseCode})"}}
            val bytes=conn.inputStream.use {input->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(2048);while(output.size()<=16384){val count=input.read(buffer,0,minOf(buffer.size,16385-output.size()));if(count<0)break;output.write(buffer,0,count)};output.toByteArray()};check(bytes.size<=16384){"Invalid ALAC bridge response"}
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally {conn.disconnect()}
    }
    fun open(uri: Uri): ParcelFileDescriptor {
        val path=uri.path.orEmpty().removePrefix("/alac")
        require(path.matches(Regex("/v1/sessions/[a-f0-9]{48}/(index\\.m3u8|init\\.mp4|[0-9]{1,6}\\.m4s)"))){"Invalid ALAC fragment path"}
        val fragment=path.endsWith(".m4s") || path.endsWith("init.mp4")
        val cache=java.io.File(context.cacheDir,"alac-fragments").apply {mkdirs()}
        val key=java.security.MessageDigest.getInstance("SHA-256").digest((endpoint+path).toByteArray()).joinToString(""){"%02x".format(it)}
        val cached=java.io.File(cache,key)
        if(fragment && cached.isFile){cached.setLastModified(System.currentTimeMillis());return ParcelFileDescriptor.open(cached,ParcelFileDescriptor.MODE_READ_ONLY)}
        val pipe=ParcelFileDescriptor.createReliablePipe()
        try { transfers.execute {
            var opened: HttpURLConnection?=null
            try {
                val conn=connection(path);opened=conn
                check(conn.responseCode==200){"ALAC fragment unavailable (${conn.responseCode})"}
                val partial=if(fragment)java.io.File.createTempFile("fragment-",".part",cache) else null
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {output->
                        conn.inputStream.use {input->
                            val file=partial?.outputStream()
                            try {
                                val bytes=ByteArray(32768);var size=0L
                                while(true){val count=input.read(bytes);if(count<0)break;size+=count
                                    check(size<=64L*1024*1024){"ALAC fragment is too large"}
                                    output.write(bytes,0,count);file?.write(bytes,0,count)
                                }
                            } finally {file?.close()}
                        }
                    }
                    partial?.renameTo(cached)
                    trim(cache)
                } finally {partial?.delete()}
            } catch(_ : Exception) {runCatching {pipe[1].closeWithError("ALAC stream interrupted. Check your bridge connection.")}}
            finally {opened?.disconnect();runCatching {pipe[1].close()}}
        }} catch(failure: Exception){pipe.forEach {it.close()};throw failure}
        return pipe[0]
    }
    companion object {
        @Synchronized private fun trim(directory: java.io.File) {
            val files=directory.listFiles()?.filter {it.isFile && !it.name.endsWith(".part")}?.sortedBy {it.lastModified()}.orEmpty()
            var bytes=files.sumOf {it.length()}
            for(file in files)if(bytes>256L*1024*1024 || System.currentTimeMillis()-file.lastModified()>24L*60*60*1000){val size=file.length();if(file.delete())bytes-=size}
        }
        private val transfers=java.util.concurrent.ThreadPoolExecutor(0,4,30,java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
        fun validateEndpoint(value: String) {
            val uri=runCatching {URI(value)}.getOrNull()
            require(uri!=null && uri.host!=null && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null &&
                (uri.scheme=="https" || (uri.scheme=="http" && uri.host in listOf("127.0.0.1","localhost","[::1]")))) {
                "Enter an HTTPS ALAC bridge address (HTTP is allowed only for a local bridge)"
            }
        }
    }
}
