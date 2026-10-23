package com.music.streamx.extensions.applemusic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMusicLibraryResourceTest {
    @Test fun catalogueAndLibraryIdentifiersStayDistinct() {
        assertEquals("albums" to "123",AppleMusicLibraryResource.type("album","albums:123"))
        assertEquals("library-albums" to "l.test",AppleMusicLibraryResource.type("album","library-albums:l.test"))
    }
    @Test(expected=IllegalArgumentException::class) fun mismatchedTypeCannotWrite() {
        AppleMusicLibraryResource.type("album","playlists:pl.test")
    }
    @Test fun savedUsesLibraryMembershipNotRating() {
        assertFalse(AppleMusicLibraryResource.saved(JSONObject("""{"type":"albums","attributes":{"rating":1}}""")))
        assertTrue(AppleMusicLibraryResource.saved(JSONObject("""{"type":"albums","relationships":{"library":{"data":[{"id":"l.saved"}]}}}""")))
        assertTrue(AppleMusicLibraryResource.saved(JSONObject("""{"type":"library-albums"}""")))
        assertFalse(AppleMusicLibraryResource.saved(JSONObject("""{"type":"albums","relationships":{"library":{"data":[]}}}""")))
    }
}
