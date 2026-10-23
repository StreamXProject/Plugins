package com.music.streamx.extensions.spotify;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpotifyNativeDownloadTest {
    @Test public void acceptsBareAndCatalogueTrackIds() {
        assertEquals("0VjIjW4GlUZAMYd2vXMi3b",SpotifyNativeDownload.track("spotify:track:0VjIjW4GlUZAMYd2vXMi3b"));
        assertEquals("0VjIjW4GlUZAMYd2vXMi3b",SpotifyNativeDownload.track("0VjIjW4GlUZAMYd2vXMi3b"));
    }
    @Test public void rejectsPathsAndOtherCatalogueEntities() {
        for(String value:new String[]{"../private","spotify:album:0VjIjW4GlUZAMYd2vXMi3b","","0VjIjW4GlUZAMYd2vXMi3b/.."}) {
            try {SpotifyNativeDownload.track(value);fail("Accepted invalid track");} catch(IllegalArgumentException expected) {}
        }
    }
}
