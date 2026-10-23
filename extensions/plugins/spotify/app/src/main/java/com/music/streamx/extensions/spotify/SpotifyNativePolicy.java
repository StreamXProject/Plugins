package com.music.streamx.extensions.spotify;

import org.json.JSONObject;

/** Reconciles async native events without inventing state or hiding initialization/key errors. */
final class SpotifyNativePolicy {
    static int bitrate(int value){return value==96 || value==160 || value==320?value:320;}
    static JSONObject status(JSONObject value,String track,boolean wanted,long elapsed,boolean accountChanged,String failure) throws Exception {
        if(accountChanged) return new JSONObject().put("account_changed",true).put("error","Spotify account changed");
        if(failure!=null) return new JSONObject().put("error",failure);
        if(value.has("error") && !value.isNull("error")) return value;
        if(wanted && elapsed>35000 && !"playing".equals(value.optString("state")) && !"ended".equals(value.optString("state")))
            return value.put("error","Native Spotify startup timed out; web playback is available");
        if(!value.optString("track_id","").equals(track)) value=new JSONObject().put("state","buffering");
        if(!wanted && "playing".equals(value.optString("state"))) value.put("state","paused");
        return value;
    }
}
