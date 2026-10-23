package com.music.streamx.extensions.applemusic

import org.junit.Assert.*
import org.junit.Test

class AppleMusicLoginPolicyTest {
    @Test fun `Apple sign in stays on exact Apple HTTPS domains`() {
        assertTrue(AppleMusicLoginPolicy.isAppleLoginUrl("https://idmsa.apple.com/appleauth/auth/authorize"))
        assertTrue(AppleMusicLoginPolicy.isAppleLoginUrl("https://authorize.music.apple.com/woa"))
        assertTrue(AppleMusicLoginPolicy.isAppleLoginUrl("about:blank"))
    }
    @Test fun `lookalike domains and unsafe navigation are blocked`() {
        listOf("https://apple.com.evil.invalid", "https://evilapple.com", "http://apple.com", "file:///sdcard/login.html",
            "javascript:alert(1)", "https://user@apple.com", "https://apple.com:1234/", "https://evil.invalid/apple.com")
            .forEach { assertFalse(it, AppleMusicLoginPolicy.isAppleLoginUrl(it)) }
    }
}
