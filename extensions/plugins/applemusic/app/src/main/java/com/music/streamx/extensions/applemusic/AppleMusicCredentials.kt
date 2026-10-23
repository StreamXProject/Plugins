package com.music.streamx.extensions.applemusic

import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** Credentials observed only in this plugin's Apple Music browser session. */
internal data class AppleMusicCredentials(val bearer: String, val userToken: String) {
    val valid: Boolean get() = bearerIsCurrent(bearer) && userToken.length in 20..16384 && userToken.none { it.isWhitespace() }
    companion object {
        fun bearerIsCurrent(token: String): Boolean = runCatching {
            require(token.length in 20..16384 && token.none { it.isWhitespace() })
            val pieces = token.split('.')
            require(pieces.size == 3)
            val body = JSONObject(String(Base64.getUrlDecoder().decode(pieces[1])))
            body.optLong("exp", 0) > System.currentTimeMillis() / 1000 + 60
        }.getOrDefault(false)
        fun isApiUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
                uri.host in setOf("api.music.apple.com", "amp-api.music.apple.com", "amp-api-edge.music.apple.com")
        }.getOrDefault(false)
        fun musicUserCookie(cookies: String?): String? = cookies?.split(';')
            ?.firstOrNull { it.trimStart().startsWith("media-user-token=") }
            ?.trimStart()?.substringAfter('=')?.takeIf { it.isNotBlank() }
    }
}
