package com.music.streamx.extensions.applemusic

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AppleMusicCredentialsTest {
    private fun bearer(exp: Long): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return encode("""{"alg":"ES256"}""") + "." + encode("""{"exp":$exp}""") + ".signature"
    }
    @Test fun `anonymous web authorization is not a connected account`() {
        val token = bearer(System.currentTimeMillis() / 1000 + 600)
        assertFalse(AppleMusicCredentials(token, "").valid)
        assertFalse(AppleMusicCredentials(token, "undefined").valid)
        assertTrue(AppleMusicCredentials(token, "signed-in-user-token-value").valid)
    }
    @Test fun `expired and malformed web authorization must be restored from browser`() {
        assertFalse(AppleMusicCredentials.bearerIsCurrent(bearer(1)))
        assertFalse(AppleMusicCredentials.bearerIsCurrent("malformed"))
        assertFalse(AppleMusicCredentials.bearerIsCurrent(bearer(System.currentTimeMillis() / 1000 + 30)))
    }
    @Test fun `only actual Apple API requests can supply session headers`() {
        assertTrue(AppleMusicCredentials.isApiUrl("https://api.music.apple.com/v1/me/storefront"))
        assertTrue(AppleMusicCredentials.isApiUrl("https://amp-api.music.apple.com/v1/me/recommendations"))
        assertTrue(AppleMusicCredentials.isApiUrl("https://amp-api-edge.music.apple.com/v1/catalog/us/search"))
        listOf("http://api.music.apple.com/", "https://api.music.apple.com.evil.invalid/", "https://evil.invalid/",
            "https://idmsa.apple.com/", "https://user@api.music.apple.com/", "https://api.music.apple.com:8080/")
            .forEach { assertFalse(it, AppleMusicCredentials.isApiUrl(it)) }
    }
    @Test fun `only exact music session cookie is read and equals signs remain intact`() {
        assertEquals("abc=def==", AppleMusicCredentials.musicUserCookie("other=private; media-user-token=abc=def==; theme=dark"))
        assertNull(AppleMusicCredentials.musicUserCookie("fake-media-user-token=value; apple-id=private"))
        assertNull(AppleMusicCredentials.musicUserCookie(null))
    }
}
