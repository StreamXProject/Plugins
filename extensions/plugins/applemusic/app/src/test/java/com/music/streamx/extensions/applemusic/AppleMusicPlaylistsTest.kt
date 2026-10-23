package com.music.streamx.extensions.applemusic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMusicPlaylistsTest {
    @Test fun creationIsPrivateAndEscapesNames() {
        val body=AppleMusicPlaylists.create("  A \"mix\"  ")
        assertEquals("A \"mix\"",body.getJSONObject("attributes").getString("name"))
        assertFalse(body.getJSONObject("attributes").getBoolean("isPublic"))
        assertEquals(body.toString(),JSONObject(body.toString()).toString())
    }
    @Test fun trackAppendPreservesLibraryTypesOrderAndRepeatedSongs() {
        val data=AppleMusicPlaylists.tracks(JSONArray(listOf("songs:123","library-songs:i.upload","songs:123"))).getJSONArray("data")
        assertEquals(3,data.length());assertEquals("123",data.getJSONObject(0).getString("id"))
        assertEquals("library-songs",data.getJSONObject(1).getString("type"))
        assertEquals("i.upload",data.getJSONObject(1).getString("id"))
    }
    @Test fun editablePickerExcludesFollowedAndUnknownPermissions() {
        val response=JSONObject("""{"data":[{"id":"p.mine","type":"library-playlists","attributes":{"name":"Mine","canEdit":true}},{"id":"p.followed","type":"library-playlists","attributes":{"name":"Followed","canEdit":false}},{"id":"p.unknown","type":"library-playlists","attributes":{"name":"Unknown"}}]}""")
        val rows=AppleMusicPlaylists.editable(response)
        assertEquals(1,rows.length());assertEquals("library-playlists:p.mine",rows.getJSONObject(0).getString("id"))
    }
    @Test(expected=IllegalArgumentException::class) fun cataloguePlaylistsAreNotWriteTargets() {AppleMusicPlaylists.playlistId("playlists:pl.catalog")}
    @Test(expected=IllegalArgumentException::class) fun nonSongsCannotBeAppended() {AppleMusicPlaylists.tracks(JSONArray(listOf("albums:123")))}
    @Test(expected=IllegalArgumentException::class) fun blankNamesCannotCreatePlaylists() {AppleMusicPlaylists.create("   ")}
    @Test fun onlyConfirmedEmptyRelationshipsCanBecomeEmptyPages() {
        assertTrue(AppleMusicPlaylists.confirmedEmpty(JSONObject("""{"relationships":{"tracks":{"data":[]}}}""")))
        assertFalse(AppleMusicPlaylists.confirmedEmpty(JSONObject("""{"attributes":{"name":"Unknown count"}}""")))
        assertFalse(AppleMusicPlaylists.confirmedEmpty(JSONObject("""{"relationships":{"tracks":{"data":[],"next":"/v1/more"}}}""")))
        assertFalse(AppleMusicPlaylists.confirmedEmpty(JSONObject("""{"relationships":{"tracks":{"data":[{"id":"one"}]}}}""")))
    }

    @Test fun removalTargetsOnlyPlaylistLibrarySongMembership() {
        assertEquals(mapOf("ids[library-songs]" to "i.saved","mode" to "all"),AppleMusicPlaylists.removalParameters("library-songs:i.saved"))
    }
    @Test(expected=IllegalArgumentException::class) fun removalRejectsCatalogueIds() {
        AppleMusicPlaylists.removalParameters("songs:123")
    }
    @Test(expected=IllegalArgumentException::class) fun removalRejectsAlbums() {
        AppleMusicPlaylists.removalParameters("library-albums:l.saved")
    }

}
