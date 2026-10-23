package com.music.streamx.extensions.spotify;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyPlaybackTest {
    @Test public void validTrackBuildsExactSpotifyPage() {
        assertEquals("https://open.spotify.com/track/1234567890123456789012", SpotifyPlayback.url("1234567890123456789012"));
    }
    @Test public void localizedSpotifyTrackPathsKeepExactIdentity() {
        String id="1234567890123456789012";
        for(String path:new String[]{"/track/"+id,"/intl-en/track/"+id,"/intl-id/track/"+id,"/intl-pt-BR/track/"+id+"/"})
            assertTrue(SpotifyPlayback.matchesPath(path,id));
        for(String path:new String[]{"/intl-en/album/"+id,"/intl-en/track/abcdefghijklmnopqrstuv","/track/"+id+"/extra","/other/track/"+id})
            assertFalse(SpotifyPlayback.matchesPath(path,id));
    }
    @Test public void untrustedInputCannotBecomeUrlOrJavascript() {
        for (String id : new String[]{null, "", "../../../", "https://evil.test/track", "1234567890123456789012';alert(1)", "12345678901234567890123"}) {
            assertNull(SpotifyPlayback.track(id));
            try { SpotifyPlayback.url(id); fail("Invalid ID must be rejected"); } catch (IllegalArgumentException expected) { }
            try { SpotifyPlayback.rendererScript(id); fail("Invalid ID must not become renderer JS"); } catch (IllegalArgumentException expected) { }
            try { SpotifyPlayback.navigateScript(id, true, "Song"); fail("Invalid ID must not become navigation JS"); } catch (IllegalArgumentException expected) { }
            try { SpotifyPlayback.script(id); fail("Invalid ID must not become JS"); } catch (IllegalArgumentException expected) { }
            try { SpotifyPlayback.selectRowScript(id); fail("Invalid ID must not become row-selection JS"); } catch (IllegalArgumentException expected) { }
        }
    }
}
