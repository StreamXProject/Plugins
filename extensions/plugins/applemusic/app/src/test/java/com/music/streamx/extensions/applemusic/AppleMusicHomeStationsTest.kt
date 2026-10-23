package com.music.streamx.extensions.applemusic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMusicHomeStationsTest {
    @Test fun personalizedStationShelvesSurviveHomeNormalization() {
        val response=JSONObject("""{"data":[{"id":"reco.1","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Top Picks for You"}},"relationships":{"contents":{"data":[{"id":"ra.discovery","type":"stations","attributes":{"name":"Discovery Station","artwork":{"url":"https://example.com/{w}x{h}.{f}"}}},{"id":"pl.favorites","type":"playlists","attributes":{"name":"Favorites Mix"}}]}}},{"id":"reco.2","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Find Your Mood"}},"relationships":{"contents":{"data":[{"id":"ra.feelgood","type":"stations","attributes":{"name":"Feel Good"}}]}}}]}""")
        val shelves=AppleMusicJson.recommendations(response)
        assertEquals(2,shelves.length())
        assertEquals("Top Picks for You",shelves.getJSONObject(0).getString("title"))
        val cards=shelves.getJSONObject(0).getJSONArray("items")
        assertEquals(2,cards.length());assertEquals("station:ra.discovery",cards.getJSONObject(0).getString("id"))
        assertEquals("collection",cards.getJSONObject(0).getString("type"))
        assertEquals("playlists:pl.favorites",cards.getJSONObject(1).getString("id"))
    }
    @Test fun showAllRecommendationPagesKeepTheirStationCards() {
        val rows=org.json.JSONArray("""[{"type":"stations","id":"ra.focus","attributes":{"name":"Focus"}},{"type":"songs","id":"123","attributes":{"name":"A song"}}]""")
        val cards=AppleMusicEditorial.cards(rows)
        assertEquals(2,cards.length());assertEquals("station:ra.focus",cards.getJSONObject(0).getString("id"))
        assertEquals("songs:123",cards.getJSONObject(1).getString("id"))
    }
    @Test fun homeDoesNotFilterOutAppleStationRecommendations() {
        assertFalse(AppleMusicRequests.homeParameters().containsKey("types"))
    }
    @Test fun stationDetailUsesAnExplicitPlayableRadioIdentity() {
        val item=AppleMusicStations.track(JSONObject("""{"type":"stations","id":"ra.discovery","attributes":{"name":"Discovery Station"}}"""))
        assertEquals("stations:ra.discovery",item.getString("id"))
        assertEquals(0,item.getInt("duration_sec"))
        assertEquals("Apple Music Radio",item.getString("artist"))
        assertEquals("ra.discovery",AppleMusicPlaybackId.resolve(item.getString("id")))
    }
}
