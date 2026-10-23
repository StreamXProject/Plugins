package com.music.streamx.extensions.spotify;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyBrowserTest {
    private static final String DRM = "android.webkit.resource.PROTECTED_MEDIA_ID";
    @Test public void grantsOnlyExactSecurePlayerOrigin() {
        assertTrue(SpotifyBrowser.protectedMediaAllowed("https://open.spotify.com/", DRM));
        for (String origin : new String[]{"http://open.spotify.com", "https://open.spotify.com.evil.test", "https://evil.test", "https://open.spotify.com:8080", "file:///"})
            assertFalse(SpotifyBrowser.protectedMediaAllowed(origin, DRM));
    }
    @Test public void neverGrantsCameraMicrophoneOrUnknownResources() {
        for (String resource : new String[]{"android.webkit.resource.VIDEO_CAPTURE", "android.webkit.resource.AUDIO_CAPTURE", "unknown"})
            assertFalse(SpotifyBrowser.protectedMediaAllowed("https://open.spotify.com", resource));
    }
}
