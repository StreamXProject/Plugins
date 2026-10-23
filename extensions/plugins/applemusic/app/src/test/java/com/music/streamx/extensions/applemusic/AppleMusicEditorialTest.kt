package com.music.streamx.extensions.applemusic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMusicEditorialTest {
    @Test fun newFeedTraversesEditorialTabsAndPreservesOrderedTracks() {
        val result=AppleMusicEditorial.sections(JSONObject("""{"data":[{"type":"groupings","relationships":{"tabs":{"data":[{"relationships":{"children":{"data":[{"attributes":{"name":"Best New Songs"},"relationships":{"room":{"data":[{"id":"42"}]},"contents":{"data":[{"type":"songs","id":"123","attributes":{"name":"First","artistName":"Artist"}},{"type":"songs","id":"124","attributes":{"name":"Second","artistName":"Artist"}}]}}}]}}}]}}}]}"""))
        assertEquals(1,result.length())
        assertEquals("Best New Songs",result.getJSONObject(0).getString("title"))
        assertEquals("browse:rooms:42",result.getJSONObject(0).getString("id"))
        assertEquals("songs:123",result.getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("id"))
    }
    @Test fun searchCategoriesKeepAppleArtworkAndNativeRoutes() {
        val result=AppleMusicEditorial.searchLanding(JSONObject("""{"data":[{"relationships":{"contents":{"data":[{"type":"apple-curators","id":"123","attributes":{"shortName":"Pop","name":"Apple Music Pop","editorialArtwork":{"subscriptionCover":{"url":"https://example.com/{w}x{h}.{f}","bgColor":"ff0000"}}}},{"type":"editorial-items","id":"456","attributes":{"name":"Concerts","link":{"url":"https://example.com"}}}]}}}]}"""))
        val items=result.getJSONArray("sections").getJSONObject(0).getJSONArray("items")
        assertEquals(1,items.length())
        assertEquals("Pop",items.getJSONObject(0).getString("title"))
        assertEquals("browse:apple-curators:123",items.getJSONObject(0).getString("id"))
        assertEquals("https://example.com/800x400.jpg",items.getJSONObject(0).getString("thumbnail_url"))
    }
    @Test fun feedBudgetPreservesShelfRoutesAndFirstItems() {
        val page=JSONObject("""{"sections":[{"id":"recommendation:1","items":[{"id":"first","title":"abcdefghijklmnop"},{"id":"second","title":"abcdefghijklmnop"},{"id":"third","title":"abcdefghijklmnop"}]},{"id":"recommendation:2","items":[{"id":"other","title":"abcdefghijklmnop"}]}]}""")
        AppleMusicPageBudget.fit(page,180)
        assertTrue(page.toString().length<=180)
        assertEquals(2,page.getJSONArray("sections").length())
        assertEquals("first",page.getJSONArray("sections").getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals("recommendation:1",page.getJSONArray("sections").getJSONObject(0).getString("id"))
    }
    @Test fun libraryTrackAndUploadsKeepTheirOriginalPlaybackIdentity() {
        assertEquals("123",AppleMusicPlaybackId.resolve("songs:123"))
        assertEquals("i.ownedUpload",AppleMusicPlaybackId.resolve("library-songs:i.ownedUpload"))
    }
    @Test(expected=IllegalArgumentException::class) fun arbitraryPlaybackPathsAreRejected() {
        AppleMusicPlaybackId.resolve("songs:https://example.com/track")
    }
}
