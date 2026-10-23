package com.music.streamx.extensions.applemusic

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

internal object AppleMusicHttp {
    class AuthorizationExpired : IllegalStateException("Apple Music authorization expired. Open your account and sign in again.")
    class RequestFailed(val status: Int,message: String): IllegalStateException(message)
    private val cooldownUntil = AtomicLong()
    fun read(url: String, headers: Map<String, String>, limit: Int = 2 * 1024 * 1024, method: String="GET", body: String?=null): String {
        require(URI(url).scheme == "https") { "HTTPS is required" }
        require(method in setOf("GET","POST","DELETE")){"Unsupported Apple Music request method"}
        require(method=="GET" || AppleMusicCredentials.isApiUrl(url)){"Writes require an Apple Music API endpoint"}
        val isApple = AppleMusicCredentials.isApiUrl(url)
        if (isApple && System.currentTimeMillis() < cooldownUntil.get()) error("Apple Music is rate limiting requests. Try again shortly.")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 12000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) ->
                require(value.none { it == '\r' || it == '\n' }) { "Invalid authorization" }
                connection.setRequestProperty(name, value)
            }
            connection.requestMethod=method
            if(body!=null) {
                val bytes=body.toByteArray(Charsets.UTF_8)
                require(bytes.size<=65536){"Playlist request is too large"}
                connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use {it.write(bytes)}
            }
            val status = connection.responseCode
            if (status == 429 && isApple) cooldownUntil.set(System.currentTimeMillis() +
                (connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 60).coerceIn(1, 3600) * 1000)
            if (status == 401) throw AuthorizationExpired()
            if(status !in 200..299) throw RequestFailed(status,
                when (status) {
                    403 -> "Apple Music denied access. Check your subscription and sign in again."
                    429 -> "Apple Music is rate limiting requests. Try again shortly."
                    else -> "Apple Music request failed (HTTP $status)"
                }
            )
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= limit) { "Apple Music response exceeded the size limit" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
}
