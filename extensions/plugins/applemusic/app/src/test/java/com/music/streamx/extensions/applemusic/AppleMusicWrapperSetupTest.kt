package com.music.streamx.extensions.applemusic

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AppleMusicWrapperSetupTest {
    @Test fun readyRequiresTheCacheAndLoginCompletion() {
        assertFalse(AppleMusicWrapperSetup.loginComplete("login successful"))
        assertFalse(AppleMusicWrapperSetup.loginComplete("account info cached successfully"))
        assertFalse(AppleMusicWrapperSetup.loginComplete("login complete, exiting"))
        assertTrue(AppleMusicWrapperSetup.loginComplete("account info cached successfully\nlogin complete, exiting\nPower down"))
    }
    @Test fun importedGuestWithoutAccountRequestsSignInInsteadOfTimingOut() {
        for (status in listOf("{}", "{\"data\":{}}", "{\"data\":{\"regions\":[]}}")) {
            try {
                AppleMusicWrapperSetup.requireSavedAccount(JSONObject(status))
                fail("A guest without a saved account must request sign-in")
            } catch (_: AppleMusicWrapperSetup.SignInRequired) { }
        }
        AppleMusicWrapperSetup.requireSavedAccount(JSONObject("{\"data\":{\"regions\":[\"us\"]}}"))
    }
    @Test fun terminalPromptsWorkAfterTranscriptWhitespaceIsTrimmed() {
        for (suffix in listOf("", " ", "\n")) {
            assertTrue(AppleMusicWrapperSetup.awaitingAccount("Apple Account:" + suffix))
            assertTrue(AppleMusicWrapperSetup.awaitingPassword("Apple Account: user\nPassword (hidden):" + suffix))
        }
        assertFalse(AppleMusicWrapperSetup.awaitingAccount("Apple Account: user"))
        assertFalse(AppleMusicWrapperSetup.awaitingPassword("Password (hidden):\nStarting QEMU"))
    }
    @Test fun verificationAcceptsOnlySixDigits() {
        assertTrue(AppleMusicWrapperSetup.validCode("123456"))
        for(value in listOf("12345","1234567","12345a","12345\n"))assertFalse(AppleMusicWrapperSetup.validCode(value))
    }
}
