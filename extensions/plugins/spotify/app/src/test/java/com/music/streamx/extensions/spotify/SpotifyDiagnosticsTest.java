package com.music.streamx.extensions.spotify;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyDiagnosticsTest {
    @Test public void cookieValuesNeverAppear() {
        String output = SpotifyDiagnostics.cookies("sp_dc=secretBearer123; sp_key=otherSecret==");
        assertTrue(output.contains("sp_dc: present (15 characters)"));
        assertTrue(output.contains("sp_key:"));
        assertFalse(output.contains("secretBearer123"));
        assertFalse(output.contains("otherSecret"));
    }
    @Test public void missingCookiesAreExplicit() {
        assertEquals("none visible\n", SpotifyDiagnostics.cookies(null));
        assertEquals("none visible\n", SpotifyDiagnostics.cookies(""));
    }
    @Test public void outputIsBoundedAndMalformedEntriesSkipped() {
        assertEquals("", SpotifyDiagnostics.cookies("malformed"));
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 100; i++) input.append("cookie").append(i).append("=hidden;");
        String output = SpotifyDiagnostics.cookies(input.toString());
        assertTrue(output.contains("cookie39:"));
        assertFalse(output.contains("cookie40:"));
        assertFalse(output.contains("hidden"));
    }
}
