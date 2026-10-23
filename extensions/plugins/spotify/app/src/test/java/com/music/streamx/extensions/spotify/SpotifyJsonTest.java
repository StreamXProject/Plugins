package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SpotifyJsonTest {
    private JSONObject song() throws Exception {
        return new JSONObject("{\"id\":\"0123456789ABCDEFGHIJKL\",\"type\":\"track\",\"name\":\"Song\",\"duration_ms\":180999,\"explicit\":true,\"artists\":[{\"id\":\"abcdefghijklmnopqrstuv\",\"name\":\"Artist\"}],\"album\":{\"id\":\"ABCDEFGHIJKLMNOPQRSTUV\",\"name\":\"Album\",\"images\":[{\"url\":\"https://example.com/cover.jpg\"}]}}");
    }
    @Test public void preservesAlbumArtistDurationAndArtwork() throws Exception {
        JSONObject item = SpotifyJson.track(song());
        assertEquals(180, item.getInt("duration_sec")); assertEquals("Artist", item.getString("artist"));
        assertEquals("Album", item.getString("album")); assertTrue(item.getBoolean("explicit"));
        assertEquals("https://example.com/cover.jpg", item.getString("thumbnail_url"));
    }
    @Test public void acceptsLegacyAndCurrentPlaylistWrappers() throws Exception {
        JSONArray source = new JSONArray().put(new JSONObject().put("track", song())).put(new JSONObject().put("item", song()));
        assertEquals(2, SpotifyJson.tracks(source).length());
    }
    @Test public void skipsDeletedTracksAndEpisodesWithoutFailingTheCollection() throws Exception {
        JSONArray source = new JSONArray().put(new JSONObject().put("track", JSONObject.NULL))
            .put(new JSONObject().put("track", song().put("type", "episode"))).put(song());
        assertEquals(1, SpotifyJson.tracks(source).length());
    }
    @Test public void albumTrackPagesInheritTheirCoverAndAlbumMetadata() throws Exception {
        JSONObject item = song(), album = item.getJSONObject("album"); item.remove("album");
        assertEquals("Album", SpotifyJson.track(item, album).getString("album"));
    }
    @Test public void malformedIdsAndUnsafeArtworkAreRejected() throws Exception {
        assertNull(SpotifyJson.track(song().put("id", "bad/id")));
        assertEquals("", SpotifyJson.cover(new JSONObject().put("images", new JSONArray().put(new JSONObject().put("url", "file:///private")))));
    }
    @Test public void playlistCardsKeepOwnerNameAndArtistCardsAllowMissingPhotos() throws Exception {
        JSONObject playlist = new JSONObject().put("id", "0123456789ABCDEFGHIJKL").put("name", "Playlist").put("owner", new JSONObject().put("display_name", "Listener"));
        assertEquals("Listener", SpotifyJson.card(playlist, "playlist").getString("subtitle"));
        assertEquals("", SpotifyJson.card(playlist, "artist").getString("thumbnail_url"));
    }
}
