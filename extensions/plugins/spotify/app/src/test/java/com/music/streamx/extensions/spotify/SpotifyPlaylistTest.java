package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyPlaylistTest {
    private static final String ID = "1234567890123456789012";
    private JSONObject response(JSONObject playlist) throws Exception { return new JSONObject().put("data", new JSONObject().put("playlistV2", playlist)); }
    private JSONObject metadata() throws Exception {
        return response(new JSONObject().put("name", "Playlist").put("ownerV2", new JSONObject().put("data", new JSONObject().put("name", "Owner")))
            .put("images", new JSONObject().put("items", new JSONArray().put(new JSONObject().put("sources", new JSONArray().put(new JSONObject().put("url", "https://example.com/art")))))));
    }
    private JSONObject trackRow() throws Exception {
        // Contents may carry the URI on the track wrapper instead of its data selection.
        return new JSONObject().put("itemV2", new JSONObject().put("_uri", "spotify:track:" + ID).put("data", new JSONObject().put("name", "Track")));
    }
    @Test public void combinesMetadataWithContentOnlyResponseAndWrapperUri() throws Exception {
        JSONObject content = new JSONObject().put("items", new JSONArray().put(trackRow()).put(trackRow())).put("totalCount", 30)
            .put("pagingInfo", new JSONObject().put("nextOffset", 25));
        JSONObject result = SpotifyPlaylist.parse(metadata(), response(new JSONObject().put("content", content)), ID, 0);
        assertEquals("Playlist", result.getString("title")); assertEquals("Owner", result.getString("subtitle"));
        assertEquals("https://example.com/art", result.getString("thumbnail_url"));
        assertEquals(2, result.getJSONArray("items").length());
        assertEquals(ID, result.getJSONArray("items").getJSONObject(0).getString("id"));
        assertEquals(25, result.getInt("next_offset"));
    }
    @Test public void emptyAndFinalPagesDoNotInventTracksOrNextOffset() throws Exception {
        JSONObject result = SpotifyPlaylist.parse(metadata(), response(new JSONObject().put("content", new JSONObject().put("items", new JSONArray()).put("totalCount", 0))), ID, 0);
        assertEquals(0, result.getJSONArray("items").length()); assertTrue(result.isNull("next_offset"));
    }
    @Test public void missingTracksAreAnErrorRatherThanEmptySuccess() throws Exception {
        try { SpotifyPlaylist.parse(metadata(), response(new JSONObject()), ID, 0); fail("Missing tracks should fail"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("tracks")); }
    }
    @Test public void mismatchedIdentityNeverProducesWrongPlaylist() throws Exception {
        JSONObject wrong = response(new JSONObject().put("uri", "spotify:playlist:abcdefghijklmnopqrstuv").put("name", "Other"));
        try { SpotifyPlaylist.parse(wrong, response(new JSONObject()), ID, 0); fail("Different playlist should fail"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("different playlist")); }
    }
}
