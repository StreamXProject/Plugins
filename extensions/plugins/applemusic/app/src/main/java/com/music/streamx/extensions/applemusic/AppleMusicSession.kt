package com.music.streamx.extensions.applemusic

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

internal class AppleMusicSession(private val context: Context) {
    private val state = context.getSharedPreferences("apple_music_session", Context.MODE_PRIVATE)
    init {
        // Retire the initial manual MusicKit configuration; browser sign-in owns the session now.
        if (state.contains("developer_input")) state.edit().clear().apply()
    }
    val generation: Long get() = state.getLong("generation", 0)
    val userToken: String get() = state.getString("user_token", "").orEmpty()
    val storefront: String get() = state.getString("storefront", "").orEmpty()
    val connected: Boolean get() = userToken.isNotBlank() && storefront.matches(Regex("[a-z]{2}"))
    fun credentials(): AppleMusicCredentials {
        check(connected) { "Sign in to Apple Music first" }
        val saved = AppleMusicCredentials(state.getString("bearer", "").orEmpty(), userToken)
        return if (saved.valid) saved else AppleMusicBrowser.restore(context, this)
    }
    fun restoreCredentials(credentials: AppleMusicCredentials, expectedGeneration: Long): Boolean = synchronized(lock) {
        if (!connected || generation != expectedGeneration || credentials.userToken != userToken || !credentials.valid) false
        else { state.edit().putString("bearer", credentials.bearer).apply(); true }
    }
    fun invalidateBearer() { state.edit().remove("bearer").apply() }
    fun confirm(credentials: AppleMusicCredentials, storefront: String, expectedGeneration: Long): Boolean = synchronized(lock) {
        require(credentials.valid && storefront.matches(Regex("[a-z]{2}"))) { "Invalid Apple Music session" }
        if (generation != expectedGeneration) false
        else {
            state.edit().putString("bearer", credentials.bearer).putString("user_token", credentials.userToken)
                .putString("storefront", storefront).putLong("generation", generation + 1).apply()
            true
        }
    }
    fun account(): JSONObject {
        check(connected) { "Sign in to Apple Music first" }
        val identity = MessageDigest.getInstance("SHA-256").digest(userToken.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        return JSONObject().put("id", identity).put("name", "Apple Music").put("email", "")
    }
    fun signOut() = synchronized(lock) {
        state.edit().remove("user_token").remove("bearer").remove("storefront").putLong("generation", generation + 1).apply()
        AppleMusicBrowser.clearCookies()
        AppleMusicEmbeddedRuntime(context).run { stop(); java.io.File(home,"config.yaml").delete() }
    }
    companion object { private val lock = Any() }
}
