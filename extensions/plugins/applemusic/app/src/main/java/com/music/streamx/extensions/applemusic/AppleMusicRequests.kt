package com.music.streamx.extensions.applemusic

import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Parameters required by Apple Music's current web Home feed, not the empty default API feed. */
internal object AppleMusicRequests {
    fun timezone(offset: ZoneOffset = ZonedDateTime.now().offset): String = if (offset == ZoneOffset.UTC) "+00:00" else offset.id
    fun homeParameters(offset: ZoneOffset = ZonedDateTime.now().offset): Map<String, String> = mapOf(
        "name" to "listen-now", "platform" to "web", "timezone" to timezone(offset),
        // Let Apple return every Home resource type, including personalized stations.
        "with" to "library",
        "include[personal-recommendation]" to "primary-content", "include[albums]" to "artists",
    )
}
