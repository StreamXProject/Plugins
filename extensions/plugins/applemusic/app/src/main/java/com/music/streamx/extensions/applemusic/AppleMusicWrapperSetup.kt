package com.music.streamx.extensions.applemusic

import org.json.JSONObject

/** Only these sanitized milestones leave the private guest console. */
internal object AppleMusicWrapperSetup {
    enum class Stage { IDLE, PREPARING, SIGN_IN, SIGNING_IN, TWO_FACTOR, STARTING, READY, ERROR }
    data class State(val stage: Stage, val message: String)
    @Volatile var state = State(Stage.IDLE, "Not connected")
    fun loginComplete(output: String) = output.contains("login complete, exiting", true) &&
        output.contains("account info cached successfully", true)
    fun awaitingAccount(output: String) = output.trimEnd().endsWith("Apple Account:")
    fun awaitingPassword(output: String) = output.trimEnd().endsWith("Password (hidden):")
    fun needsCode(output: String) = output.substringAfterLast("2FA code:", "").isBlank() && output.contains("2FA code:")
    class SignInRequired : IllegalStateException("Sign in to Apple Music for local playback")
    fun requireSavedAccount(status: JSONObject) {
        if (status.optJSONObject("data")?.optJSONArray("regions")?.length()?.let { it > 0 } != true)
            throw SignInRequired()
    }
    fun validCode(code: String) = code.matches(Regex("[0-9]{6}"))
}
