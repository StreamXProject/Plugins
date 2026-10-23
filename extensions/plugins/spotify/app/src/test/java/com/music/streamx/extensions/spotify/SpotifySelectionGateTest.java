package com.music.streamx.extensions.spotify;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifySelectionGateTest {
    private JSONObject local() throws Exception {
        return new JSONObject().put("selection_restricted",true).put("media_present",true)
            .put("media_kind","AUDIO").put("ready_state",4).put("paused",false);
    }
    @Test public void staleRemoteMetadataCannotFailStartup() throws Exception {
        SpotifySelectionGate gate=new SpotifySelectionGate();
        JSONObject remote=new JSONObject().put("selection_restricted",true).put("ready_state",-1);
        assertFalse(gate.failed(remote,true,0));
        assertFalse(gate.failed(remote,true,60000));
    }
    @Test public void requiresStableLocalMismatch() throws Exception {
        SpotifySelectionGate gate=new SpotifySelectionGate();JSONObject audio=local();
        assertFalse(gate.failed(audio,true,100));
        assertFalse(gate.failed(audio,true,2599));
        assertTrue(gate.failed(audio,true,2600));
    }
    @Test public void bufferingAndPauseResetTheMismatch() throws Exception {
        SpotifySelectionGate gate=new SpotifySelectionGate();JSONObject audio=local();
        assertFalse(gate.failed(audio,true,100));
        assertFalse(gate.failed(audio.put("ready_state",1),true,2500));
        assertFalse(gate.failed(audio.put("ready_state",4),true,2600));
        assertFalse(gate.failed(audio.put("paused",true),true,5000));
        assertFalse(gate.failed(audio.put("paused",false),true,5100));
    }
    @Test public void pauseRequestMatchingTrackAndCanvasNeverFail() throws Exception {
        SpotifySelectionGate gate=new SpotifySelectionGate();JSONObject audio=local();
        assertFalse(gate.failed(audio,false,10000));
        assertFalse(gate.failed(audio.put("selection_restricted",false),true,20000));
        assertFalse(gate.failed(audio.put("selection_restricted",true).put("media_kind","VIDEO"),true,30000));
        assertFalse(gate.failed(audio,true,40000));
    }
}
