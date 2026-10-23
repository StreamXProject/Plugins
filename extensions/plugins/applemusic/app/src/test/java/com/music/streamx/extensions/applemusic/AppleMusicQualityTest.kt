package com.music.streamx.extensions.applemusic

import org.junit.Assert.*
import org.junit.Test

class AppleMusicQualityTest {
    @Test fun onlySupportedAacPreferencesAreAccepted() {
        assertEquals(256,AppleMusicQuality.DEFAULT_KBPS)
        assertEquals(64,AppleMusicQuality.validate(64))
        assertEquals(256,AppleMusicQuality.validate(256))
    }
    @Test(expected=IllegalArgumentException::class) fun losslessOrUnadvertisedRatesAreNotInvented() {
        AppleMusicQuality.validate(320)
    }
}
