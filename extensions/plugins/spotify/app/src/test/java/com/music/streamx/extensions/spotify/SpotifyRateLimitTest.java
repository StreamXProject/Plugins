package com.music.streamx.extensions.spotify;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyRateLimitTest {
    @Test public void honorsServerSecondsWithoutOneHourCap() {
        assertEquals(7201000L, SpotifyRateLimit.deadline("7200", 1000));
        assertEquals(2000L, SpotifyRateLimit.deadline("0", 1000));
    }
    @Test public void missingInvalidAndNegativeUseFallback() {
        for (String header : new String[]{null, "", "invalid", "-1"}) {
            assertEquals(61000L, SpotifyRateLimit.deadline(header, 1000));
            assertFalse(SpotifyRateLimit.specified(header));
        }
    }
    @Test public void countdownRoundsUpAndExpires() {
        assertEquals(2, SpotifyRateLimit.remaining(2001, 1000));
        assertEquals(1, SpotifyRateLimit.remaining(2000, 1000));
        assertEquals(0, SpotifyRateLimit.remaining(1000, 1000));
        assertEquals(0, SpotifyRateLimit.remaining(999, 1000));
    }
    @Test public void veryLongHeadersDoNotOverflow() {
        assertEquals(Long.MAX_VALUE, SpotifyRateLimit.deadline(Long.toString(Long.MAX_VALUE), 1000));
        assertTrue(SpotifyRateLimit.remaining(Long.MAX_VALUE, 1000) > 0);
    }
    @Test public void cooldownIsSharedAndKeepsHeaderProvenance() {
        long now = 10000000000000L;
        SpotifyRateLimit.Limited first = SpotifyRateLimit.record(null, now);
        try { SpotifyRateLimit.check(now + 1); fail("Expected shared cooldown"); }
        catch (SpotifyRateLimit.Limited second) {
            assertEquals(first.until, second.until); assertFalse(second.serverSpecified);
        }
        SpotifyRateLimit.Limited longer = SpotifyRateLimit.record("120", now);
        assertTrue(longer.serverSpecified);
        assertEquals(now + 120000, longer.until);
        assertEquals(longer.until, SpotifyRateLimit.record("1", now).until);
        SpotifyRateLimit.check(longer.until);
    }
}
