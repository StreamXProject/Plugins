package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyGraphTest {
    private static final String ID = "1234567890123456789012";
    private JSONObject album() throws Exception {
        return new JSONObject().put("uri", "spotify:album:" + ID).put("name", "Album")
            .put("coverArt", new JSONObject().put("sources", new JSONArray().put(new JSONObject().put("url", "https://example.com/cover"))));
    }
    private JSONObject track() throws Exception {
        return new JSONObject().put("uri", "spotify:track:" + ID).put("name", "Song").put("albumOfTrack", album())
            .put("artists", new JSONObject().put("items", new JSONArray().put(new JSONObject().put("uri", "spotify:artist:" + ID).put("profile", new JSONObject().put("name", "Artist")))))
            .put("trackDuration", new JSONObject().put("totalMilliseconds", 185000));
    }
    @Test public void homeParsesWrappedMusicSectionsAndSkipsUnsupportedItems() throws Exception {
        JSONArray items = new JSONArray().put(new JSONObject().put("content", new JSONObject().put("data", album())))
            .put(new JSONObject().put("content", new JSONObject().put("data", new JSONObject().put("uri", "spotify:episode:" + ID).put("name", "Podcast"))));
        JSONObject section = new JSONObject().put("uri", "spotify:section:example")
            .put("data", new JSONObject().put("title", new JSONObject().put("transformedLabel", "Made for you")))
            .put("sectionItems", new JSONObject().put("items", items));
        JSONObject response = new JSONObject().put("data", new JSONObject().put("home", new JSONObject().put("sectionContainer", new JSONObject().put("sections", new JSONObject().put("items", new JSONArray().put(section))))));
        JSONObject shelf = SpotifyGraph.home(response).getJSONArray("sections").getJSONObject(0);
        assertEquals("Made for you", shelf.getString("title"));
        assertEquals("home:spotify:section:example", shelf.getString("id"));
        assertEquals(1, shelf.getJSONArray("items").length());
        assertEquals("https://example.com/cover", shelf.getJSONArray("items").getJSONObject(0).getString("thumbnail_url"));
    }
    @Test public void trackWrappersPreserveCreditAndDuration() throws Exception {
        JSONObject result = SpotifyGraph.item(new JSONObject().put("itemV2", new JSONObject().put("data", track())), null);
        assertEquals("Artist", result.getString("artist"));
        assertEquals(ID, result.getString("artist_id"));
        assertEquals(185, result.getInt("duration_sec"));
        assertEquals("Album", result.getString("album"));
    }
    @Test public void albumTracksUseFallbackCoverAndKeepDuplicates() throws Exception {
        JSONObject track = track(); track.remove("albumOfTrack");
        JSONObject row = new JSONObject().put("track", track);
        JSONArray result = SpotifyGraph.items(new JSONObject().put("items", new JSONArray().put(row).put(row)), album(), 50);
        assertEquals(2, result.length()); assertEquals("https://example.com/cover", result.getJSONObject(0).getString("thumbnail_url"));
    }
    @Test public void pagingEndsAndNeverReturnsCurrentOffset() throws Exception {
        assertEquals(20, SpotifyGraph.next(new JSONObject().put("pagingInfo", new JSONObject().put("nextOffset", 20)), 0));
        assertSame(JSONObject.NULL, SpotifyGraph.next(new JSONObject().put("pagingInfo", new JSONObject().put("nextOffset", 20)), 20));
        assertSame(JSONObject.NULL, SpotifyGraph.next(new JSONObject().put("pagingInfo", new JSONObject().put("nextOffset", JSONObject.NULL)), 0));
        assertEquals(2, SpotifyGraph.next(new JSONObject().put("items", new JSONArray().put(1).put(2)).put("totalCount", 3), 0));
    }
    @Test public void artistCardsAndLibraryWrappersWork() throws Exception {
        JSONObject artist = new JSONObject().put("uri", "spotify:artist:" + ID).put("profile", new JSONObject().put("name", "Name"));
        JSONObject result = SpotifyGraph.item(new JSONObject().put("item", new JSONObject().put("data", artist)), null);
        assertEquals("artist", result.getString("type")); assertEquals("Name", result.getString("title"));
    }
    @Test public void malformedHomeFailsInsteadOfReportingSuccess() throws Exception {
        try { SpotifyGraph.home(new JSONObject()); fail("Missing home should fail"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("home feed")); }
        assertNull(SpotifyGraph.item(new JSONObject().put("uri", "spotify:track:invalid").put("name", "invalid"), null));
    }
}
