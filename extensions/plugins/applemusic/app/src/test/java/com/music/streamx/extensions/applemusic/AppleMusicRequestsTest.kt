package com.music.streamx.extensions.applemusic

import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class AppleMusicRequestsTest {
    @Test fun `web Home supplies its feed name and the mandatory signed timezone offset`() {
        val params = AppleMusicRequests.homeParameters(ZoneOffset.of("+05:30"))
        assertEquals("listen-now", params["name"])
        assertEquals("web", params["platform"])
        assertEquals("+05:30", params["timezone"])
    }
    @Test fun `UTC and negative offsets keep the web player's expected format`() {
        assertEquals("+00:00", AppleMusicRequests.timezone(ZoneOffset.UTC))
        assertEquals("-07:00", AppleMusicRequests.timezone(ZoneOffset.of("-07:00")))
    }
}
