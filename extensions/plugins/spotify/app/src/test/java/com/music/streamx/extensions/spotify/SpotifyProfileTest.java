package com.music.streamx.extensions.spotify;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyProfileTest {
    @Test public void extractsWebPlayerProfileAndHttpsAvatar() throws Exception {
        JSONObject result = SpotifyProfile.parse(new JSONObject("{\"data\":{\"me\":{\"profile\":{\"uri\":\"spotify:user:example\",\"name\":\"Example\",\"avatar\":{\"sources\":[{\"url\":\"http://unsafe\"},{\"url\":\"https://example.com/avatar\"}]}}}}}"));
        assertEquals("spotify:user:example", result.getString("id"));
        assertEquals("Example", result.getString("name"));
        assertEquals("https://example.com/avatar", result.getString("thumbnail_url"));
    }
    @Test public void profileWithoutOptionalFieldsWorks() throws Exception {
        JSONObject result = SpotifyProfile.parse(new JSONObject("{\"data\":{\"me\":{\"profile\":{\"uri\":\"spotify:user:example\"}}}}"));
        assertEquals("example", result.getString("name")); assertEquals("", result.getString("thumbnail_url"));
    }
    @Test public void missingIdentityAndGraphErrorsNeverVerify() throws Exception {
        for (String input : new String[]{"{}", "{\"data\":{\"me\":null}}", "{\"errors\":[{\"message\":\"private secret\"}]}"}) {
            try { SpotifyProfile.parse(new JSONObject(input)); fail("Must not verify an invalid profile"); }
            catch (IllegalStateException expected) { assertFalse(expected.getMessage().contains("private secret")); }
        }
    }
}
