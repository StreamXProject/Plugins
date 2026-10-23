package com.music.streamx.extensions.applemusic

import org.junit.Assert.*
import org.junit.Test

class AppleMusicAlacPlaybackIdTest {
    @Test fun resolvedCatalogueSongIsNotUnwrappedTwice() {
        val resolved=AppleMusicPlaybackId.resolve("songs:1624945512")
        assertEquals("1624945512",AppleMusicPlaybackId.catalogueForAlac(resolved))
    }
    @Test fun unresolvedUploadsAndStationsCannotBecomeAlacSongs() {
        for(id in listOf("i.example","ra.example","songs:123","123/../456","")) {
            assertThrows(IllegalArgumentException::class.java){AppleMusicPlaybackId.catalogueForAlac(id)}
        }
    }
}
