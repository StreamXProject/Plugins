package com.music.streamx.extensions.applemusic

/** MusicKit's AAC quality preferences. This describes a request, not measured stream telemetry. */
internal object AppleMusicQuality {
    const val DEFAULT_KBPS=256
    val supported=listOf(64,256)
    fun validate(value: Int): Int {
        require(value in supported){"Apple Music web playback supports 64 or 256 kbps AAC"}
        return value
    }
}
