package com.music.streamx.extensions.applemusic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMusicJsonTest {
    private fun resource(type: String = "songs", id: String = "123") = JSONObject("""{
      "id":"$id","type":"$type","attributes":{"name":"Track","artistName":"Artist","albumName":"Album",
      "durationInMillis":184970,"contentRating":"explicit","artwork":{"url":"https://is1-ssl.mzstatic.com/image/{w}x{h}{c}.{f}"}},
      "relationships":{"albums":{"data":[{"type":"albums","id":"987"}]}}
    }""")

    @Test fun `song metadata matches native source contract`() {
        val row = AppleMusicJson.item(resource())!!
        assertEquals("track", row.getString("type")); assertEquals("songs:123", row.getString("id"))
        assertEquals(184, row.getInt("duration_sec")); assertTrue(row.getBoolean("explicit"))
        assertEquals("albums:987", row.getString("album_id"))
        assertEquals("https://is1-ssl.mzstatic.com/image/600x600bb.jpg", row.getString("thumbnail_url"))
    }
    @Test fun `library and catalogue IDs do not collide`() {
        assertNotEquals(AppleMusicJson.item(resource())!!.getString("id"), AppleMusicJson.item(resource("library-songs"))!!.getString("id"))
        assertEquals("library-playlists" to "p.abc-12", AppleMusicJson.splitId("library-playlists:p.abc-12"))
    }
    @Test fun `unsupported videos and stations are omitted`() {
        assertNull(AppleMusicJson.item(resource("music-videos")))
        assertNull(AppleMusicJson.item(resource("stations")))
    }
    @Test fun `ordered repeated playlist tracks remain repeated`() {
        val rows = AppleMusicJson.items(JSONArray().put(resource(id = "1")).put(resource(id = "2")).put(resource(id = "1")))
        assertEquals(listOf("songs:1", "songs:2", "songs:1"), (0 until rows.length()).map { rows.getJSONObject(it).getString("id") })
    }
    @Test fun `missing metadata is omitted rather than invented`() {
        assertNull(AppleMusicJson.item(JSONObject().put("id", "123").put("type", "songs")))
        assertNull(AppleMusicJson.item(resource().apply { getJSONObject("attributes").put("name", "") }))
    }
    @Test fun `recommendation shelves preserve title and nested content order`() {
        val inner = JSONObject().put("id", "inner").put("type", "personal-recommendation")
            .put("attributes", JSONObject().put("title", JSONObject().put("stringForDisplay", "Made for you")))
            .put("relationships", JSONObject().put("contents", JSONObject().put("data", JSONArray().put(resource("albums", "55")).put(resource("playlists", "pl.33")))))
        val outer = JSONObject().put("id", "outer").put("relationships", JSONObject().put("contents", JSONObject().put("data", JSONArray().put(inner))))
        val result = AppleMusicJson.recommendations(JSONObject().put("data", JSONArray().put(outer)))
        assertEquals(1, result.length()); assertEquals("Made for you", result.getJSONObject(0).getString("title"))
        assertEquals("recommendation:inner", result.getJSONObject(0).getString("id"))
        assertEquals("playlist", result.getJSONObject(0).getJSONArray("items").getJSONObject(1).getString("type"))
    }
    @Test fun `pagination accepts increasing offsets and terminates malformed or repeated pages`() {
        fun page(next: String) = JSONObject().put("next", next)
        assertEquals(25, AppleMusicJson.nextOffset(page("/v1/me/library/songs?offset=25&limit=25"), 0))
        assertEquals(25, AppleMusicJson.nextOffset(page("https://amp-api.music.apple.com/v1/me/library/songs?offset=25"), 0))
        assertEquals(JSONObject.NULL, AppleMusicJson.nextOffset(page("/v1/me/library/songs?offset=25"), 25))
        assertEquals(JSONObject.NULL, AppleMusicJson.nextOffset(page("/v1/me/library/songs?offset=garbage"), 0))
        assertEquals(JSONObject.NULL, AppleMusicJson.nextOffset(page("https://evil.invalid/v1/songs?offset=25"), 0))
        assertEquals(JSONObject.NULL, AppleMusicJson.nextOffset(JSONObject(), 0))
    }
    @Test fun `IDs cannot inject paths queries or resource types`() {
        for (id in listOf("songs:../users", "songs:123?token=x", "songs:123/track", "unknown:123")) {
            assertTrue(runCatching { AppleMusicJson.splitId(id) }.isFailure)
        }
    }
    @Test fun `insecure artwork is omitted`() {
        val source = resource().apply { getJSONObject("attributes").getJSONObject("artwork").put("url", "http://example.com/image") }
        assertEquals("", AppleMusicJson.item(source)!!.getString("thumbnail_url"))
    }
}
