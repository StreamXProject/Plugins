package com.music.streamx.extensions.spotify;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpotifyPlanTest {
    private JSONObject plan(String product) throws Exception {
        return SpotifyPlan.parse(new JSONObject().put("data",new JSONObject().put("me",new JSONObject().put("account",new JSONObject().put("product",product)))));
    }
    @Test public void freeAndExplicitPlatinumAreClassified() throws Exception {
        assertEquals("free",plan("FREE").getString("tier"));
        assertEquals("platinum",plan("PLATINUM").getString("tier"));
        assertEquals("platinum",plan("PREMIUM_PLATINUM").getString("tier"));
    }
    @Test public void premiumProductsNeverImplyPlatinumOrLossless() throws Exception {
        for(String product:new String[]{"PREMIUM","FAMILY","STUDENT","DUO"}) {
            JSONObject result=plan(product);
            assertEquals("premium",result.getString("tier"));
            assertFalse(result.getBoolean("exact_tier"));
        }
    }
    @Test public void unknownAndMissingProductsAreNotFree() throws Exception {
        assertEquals("unknown",plan("FUTURE_PRODUCT").getString("tier"));
        assertEquals("unknown",plan("").getString("tier"));
        try {SpotifyPlan.parse(new JSONObject());fail();}catch(IllegalStateException expected){}
    }
    @Test public void permitsPlaybackOnlyForPremiumOrPlatinum() throws Exception {
        assertFalse(SpotifyPlan.permitsPlayback(plan("FREE")));
        assertFalse(SpotifyPlan.permitsPlayback(new JSONObject().put("tier", "unknown")));
        assertFalse(SpotifyPlan.permitsPlayback(new JSONObject()));
        assertTrue(SpotifyPlan.permitsPlayback(plan("PREMIUM")));
        assertTrue(SpotifyPlan.permitsPlayback(plan("FAMILY")));
        assertTrue(SpotifyPlan.permitsPlayback(plan("STUDENT")));
        assertTrue(SpotifyPlan.permitsPlayback(plan("DUO")));
        assertTrue(SpotifyPlan.permitsPlayback(plan("PLATINUM")));
    }
}
