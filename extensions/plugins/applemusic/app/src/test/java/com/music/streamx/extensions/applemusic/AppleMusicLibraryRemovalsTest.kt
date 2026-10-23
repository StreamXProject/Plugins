package com.music.streamx.extensions.applemusic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMusicLibraryRemovalsTest {
    @Test fun onlyConcreteLibraryIdsAreRemovalTargets() {
        assertEquals("library-playlists" to "p.own",AppleMusicLibraryRemovals.resource("playlist","library-playlists:p.own"))
        assertEquals("library-artists" to "r.artist",AppleMusicLibraryRemovals.resource("artist","library-artists:r.artist"))
    }
    @Test(expected=IllegalArgumentException::class) fun catalogueResourceCannotBeDeleted() {
        AppleMusicLibraryRemovals.resource("album","albums:123")
    }
    @Test(expected=IllegalArgumentException::class) fun wrongKindCannotDeleteAnotherResource() {
        AppleMusicLibraryRemovals.resource("artist","library-playlists:p.own")
    }
    @Test fun artistRemovalKeepsExactAlbumIdsAndDeduplicates() {
        val response=JSONObject("""{"data":[{"type":"library-albums","id":"l.one"},{"type":"library-albums","id":"l.two"},{"type":"library-albums","id":"l.one"}]}""")
        assertEquals(listOf("library-albums:l.one","library-albums:l.two"),AppleMusicLibraryRemovals.artistAlbums(response))
    }
    @Test(expected=IllegalArgumentException::class) fun artistRelationshipCannotInjectPlaylistDeletion() {
        AppleMusicLibraryRemovals.artistAlbums(JSONObject("""{"data":[{"type":"library-playlists","id":"p.other"}]}"""))
    }
    @Test fun libraryArtistUsesCataloguePortraitWithoutChangingLibraryIdentity() {
        val item=AppleMusicJson.item(JSONObject("""{"id":"r.artist","type":"library-artists","attributes":{"name":"Library Artist"},"relationships":{"catalog":{"data":[{"id":"123","type":"artists","attributes":{"name":"Catalog Artist","artwork":{"url":"https://example.com/{w}x{h}.jpg"}}}]}}}"""))!!
        assertEquals("library-artists:r.artist",item.getString("id"))
        assertEquals("Library Artist",item.getString("title"))
        assertEquals("https://example.com/600x600.jpg",item.getString("thumbnail_url"))
    }
    @Test fun savedCatalogueTickResolvesConcreteLibraryId() {
        val response=JSONObject("""{"data":[{"id":"123","type":"albums","relationships":{"library":{"data":[{"id":"l.saved","type":"library-albums"}]}}}]}""")
        assertEquals("library-albums:l.saved",AppleMusicLibraryRemovals.libraryId("album","albums:123",response))
    }
    @Test(expected=IllegalArgumentException::class) fun catalogueResolutionCannotDeleteDifferentResourceKind() {
        val response=JSONObject("""{"data":[{"relationships":{"library":{"data":[{"id":"p.other","type":"library-playlists"}]}}}]}""")
        AppleMusicLibraryRemovals.libraryId("album","albums:123",response)
    }
    @Test(expected=IllegalStateException::class) fun missingLibraryMembershipCannotBecomeDeletion() {
        AppleMusicLibraryRemovals.libraryId("album","albums:123",JSONObject("""{"data":[{"relationships":{"library":{"data":[]}}}]}"""))
    }

}
