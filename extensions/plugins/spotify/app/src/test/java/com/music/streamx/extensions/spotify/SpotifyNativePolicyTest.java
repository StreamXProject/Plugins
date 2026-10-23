package com.music.streamx.extensions.spotify;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyNativePolicyTest {
    @Test public void authenticationFailureCannotBeHiddenAsWaitingForTrack() throws Exception {
        JSONObject error=new JSONObject().put("error","Native authentication refused");
        assertEquals("Native authentication refused",SpotifyNativePolicy.status(error,"track",true,100,false,null).getString("error"));
    }
    @Test public void outgoingTracksPositionDoesNotBecomeTheNewTracksPosition() throws Exception {
        JSONObject old=new JSONObject().put("track_id","old").put("state","playing").put("position_ms",88000);
        JSONObject next=SpotifyNativePolicy.status(old,"new",true,100,false,null);
        assertEquals("buffering",next.getString("state"));assertFalse(next.has("position_ms"));
    }
    @Test public void pausedIntentWinsOverALatePlayingEvent() throws Exception {
        JSONObject playing=new JSONObject().put("track_id","track").put("state","playing").put("position_ms",12000);
        JSONObject paused=SpotifyNativePolicy.status(playing,"track",false,100,false,null);
        assertEquals("paused",paused.getString("state"));assertEquals(12000,paused.getInt("position_ms"));
    }
    @Test public void accountChangeStopsInsteadOfFallingBackUnderAnotherAccount() throws Exception {
        assertTrue(SpotifyNativePolicy.status(new JSONObject(),"track",true,100,true,null).getBoolean("account_changed"));
    }
    @Test public void nativeStartupIsBoundedButPausedTracksDoNotTimeOut() throws Exception {
        JSONObject loading=new JSONObject().put("track_id","track").put("state","buffering");
        assertTrue(SpotifyNativePolicy.status(new JSONObject(loading.toString()),"track",true,40000,false,null).has("error"));
        assertFalse(SpotifyNativePolicy.status(loading,"track",false,40000,false,null).has("error"));
    }
    @Test public void unsupportedQualityCannotBecomeAnUnvalidatedNativeRequest(){
        for(int rate:new int[]{96,160,320})assertEquals(rate,SpotifyNativePolicy.bitrate(rate));
        for(int rate:new int[]{0,-1,999})assertEquals(320,SpotifyNativePolicy.bitrate(rate));
    }
}
