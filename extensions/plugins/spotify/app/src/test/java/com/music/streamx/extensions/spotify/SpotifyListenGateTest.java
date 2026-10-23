package com.music.streamx.extensions.spotify;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
public class SpotifyListenGateTest {
    @Test public void onlyAdvancingAudibleTimeCreatesOneListen() {
        SpotifyListenGate gate=new SpotifyListenGate();
        assertFalse(gate.sample(false,0,0));
        for(int n=1;n<=5;n++)assertFalse(gate.sample(true,n*1000,n*1000));
        assertTrue(gate.sample(true,6000,6000));
        assertFalse(gate.sample(true,7000,7000));
    }
    @Test public void pausesLoadingFrozenClocksAndSeeksNeverCountAsListens() {
        SpotifyListenGate gate=new SpotifyListenGate();
        for(int n=0;n<20;n++)assertFalse(gate.sample(true,0,n*1000));
        assertFalse(gate.sample(true,150000,21000));
        assertFalse(gate.sample(false,150000,22000));
        assertFalse(gate.sample(true,150000,500000));
        assertFalse(gate.sample(true,151000,501000));
    }
    @Test public void combinesLocalAndRemoteHistoryWithoutDuplicatingTheSameListen() throws Exception {
        JSONObject local=new JSONObject().put("id","a").put("played_at_ms",100000);
        JSONObject remote=new JSONObject().put("id","a").put("played_at_ms",100500);
        JSONObject earlier=new JSONObject().put("id","a").put("played_at_ms",1000);
        JSONArray merged=SpotifyLocalHistory.merge(new JSONArray().put(local),new JSONArray().put(remote).put(earlier)).getJSONArray("items");
        assertEquals(2,merged.length());assertEquals(100000,merged.getJSONObject(0).getLong("played_at_ms"));
    }
}
