package com.music.streamx.extensions.applemusic

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class AppleMusicWrapperAddressTest {
    @Test fun embeddedCredentialsAreHiddenAndPreserved() {
        val value=AppleMusicWrapperAddress.parse("https://amdl:example%40secret@wrapper.example.test/base/")
        assertEquals("https://wrapper.example.test/base",value.address)
        assertEquals("amdl",value.username)
        assertEquals("example@secret",value.password)
        assertEquals("amdl:example@secret",URI(value.authenticated).userInfo)
        assertFalse(value.toString().contains("example@secret"))
    }
    @Test fun separateCredentialsRoundTripReservedCharacters() {
        val value=AppleMusicWrapperAddress.parse("https://wrapper.example.test", "amdl", "a+b:@/#?%")
        assertEquals("amdl:a+b:@/#?%",URI(value.authenticated).userInfo)
    }
    @Test fun publicHttpAndQueryCredentialsAreRejected() {
        for(url in listOf("http://public.example.test", "https://wrapper.example.test?token=bad", "https://wrapper.example.test/#fragment", "file:///tmp/wrapper")) {
            assertThrows(IllegalArgumentException::class.java) {AppleMusicWrapperAddress.parse(url)}
        }
        assertEquals("http://127.0.0.1:12340",AppleMusicWrapperAddress.parse("http://127.0.0.1:12340").address)
    }
    @Test fun logsRedactCredentialsInWrapperUrls() {
        val line=AppleMusicTermuxLog.sanitize("GET https://amdl:private-password@wrapper.example.test/status media-user-token=private-token")
        assertFalse(line.contains("private-password"))
        assertFalse(line.contains("private-token"))
        assertTrue(line.contains("wrapper.example.test"))
    }
}
