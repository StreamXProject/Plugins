package com.music.streamx.extensions.applemusic

import java.net.URI

internal object AppleMusicLoginPolicy {
    fun isAppleLoginUrl(value: String): Boolean {
        if (value == "about:blank") return true
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "apple.com" || host.endsWith(".apple.com"))
    }
}
