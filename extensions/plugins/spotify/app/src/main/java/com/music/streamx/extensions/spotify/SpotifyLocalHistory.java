package com.music.streamx.extensions.spotify;
import android.content.Context;
import org.json.*;

/** Private, bounded journal keyed by the verified Spotify user URI, independent of remote history permission. */
final class SpotifyLocalHistory {
    static String identity(Context context) {
        android.content.SharedPreferences session=context.getSharedPreferences("spotify_session",0);
        return session.getBoolean("confirmed",false)&&session.getBoolean("verified",false)?session.getString("profile_id",""):"";
    }
    static synchronized JSONArray read(Context context) {
        String identity=identity(context);
        if(!identity.startsWith("spotify:user:"))return new JSONArray();
        try {return new JSONArray(context.getSharedPreferences("spotify_local_history",0).getString(identity,"[]"));}
        catch(Exception ignored){return new JSONArray();}
    }
    static synchronized void record(Context context,String identity,JSONObject metadata) throws Exception {
        if(!identity.startsWith("spotify:user:") || !identity.equals(identity(context)))return;
        JSONArray previous=read(context),next=new JSONArray();
        next.put(new JSONObject(metadata.toString()).put("played_at_ms",System.currentTimeMillis()));
        for(int n=0;n<Math.min(previous.length(),99);n++)next.put(previous.getJSONObject(n));
        context.getSharedPreferences("spotify_local_history",0).edit().putString(identity,next.toString()).apply();
    }
    static JSONObject merge(JSONArray local,JSONArray remote) throws Exception {
        java.util.List<JSONObject> all=new java.util.ArrayList<>();
        for(JSONArray source:new JSONArray[]{local,remote})for(int n=0;n<source.length();n++) {
            JSONObject item=source.optJSONObject(n);if(item==null)continue;
            boolean duplicate=false;
            for(JSONObject existing:all)if(existing.optString("id").equals(item.optString("id")) && existing.optLong("played_at_ms")>0 && item.optLong("played_at_ms")>0
                && Math.abs(existing.optLong("played_at_ms")-item.optLong("played_at_ms"))<60000) {duplicate=true;break;}
            if(!duplicate)all.add(item);
        }
        all.sort((a,b)->Long.compare(b.optLong("played_at_ms"),a.optLong("played_at_ms")));
        JSONArray result=new JSONArray();for(int n=0;n<Math.min(all.size(),100);n++)result.put(all.get(n));
        return new JSONObject().put("items",result);
    }
}
