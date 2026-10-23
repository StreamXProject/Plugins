package com.music.streamx.extensions.spotify;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import org.json.JSONObject;
import java.util.concurrent.Executors;

/** Signature-checked IPC transport shared by native and browser engines; no queue is kept here. */
final class SpotifyPlaybackBridge {
    private static final java.util.concurrent.ExecutorService CLEANUP=Executors.newSingleThreadExecutor();
    private static SpotifyNativePlayer nativePlayer;
    private static Context context;
    private static IBinder owner;
    private static IBinder.DeathRecipient death;
    private static Bundle lastLoad;
    private static boolean desiredPlay;
    private static String nativeFailure;
    private static long generation;
    private static SpotifyListenGate listenGate=new SpotifyListenGate();
    private static String historyIdentity="";
    private static JSONObject historyMetadata;
    private static String observe(String value) throws Exception {
        JSONObject status=new JSONObject(value);
        if(context!=null && historyMetadata!=null && listenGate.sample("playing".equals(status.optString("state")),status.optLong("position_ms"),android.os.SystemClock.elapsedRealtime()))
            try {SpotifyLocalHistory.record(context,historyIdentity,historyMetadata);}catch(Exception ignored){}
        return value;
    }
    private static int rate;
    private static long failedGeneration=-1,failedUntil;
    static synchronized String call(Context app,Bundle args) throws Exception {
        String command=args.getString("command","status");
        IBinder caller=args.getBinder("owner");
        if(!command.equals("load") && owner!=null && caller!=owner) throw new IllegalStateException("Playback belongs to another host session");
        if(command.equals("load")) {
            if(caller==null || !caller.isBinderAlive() || SpotifyPlayback.track(args.getString("track_id"))==null) throw new IllegalArgumentException("Invalid playback request");
            long nextGeneration=app.getSharedPreferences("spotify_session",0).getLong("generation",0);
            int nextRate=SpotifyNativePolicy.bitrate(app.getSharedPreferences("spotify_session",0).getInt("native_kbps",320));
            boolean useNative=SpotifyNativeEngine.AVAILABLE && app.getSharedPreferences("spotify_session",0).getBoolean("native_enabled",false)
                && !(failedGeneration==nextGeneration && failedUntil>System.currentTimeMillis());
            if(owner!=caller) {
                stopAll();owner=caller;context=app.getApplicationContext();
                IBinder owned=caller;death=()->CLEANUP.execute(()->{synchronized(SpotifyPlaybackBridge.class){if(owner==owned)stopAll();}});
                owner.linkToDeath(death,0);
            }
            listenGate=new SpotifyListenGate(); historyIdentity=SpotifyLocalHistory.identity(app);
            try {historyMetadata=new JSONObject(args.getString("track_metadata","{}"));}catch(Exception ignored){historyMetadata=new JSONObject();}
            historyMetadata.put("id",args.getString("track_id")).put("type","track");
            if(historyMetadata.optString("title").isBlank())historyMetadata.put("title",args.getString("track_title",args.getString("track_id")));
            nativeFailure=null;lastLoad=new Bundle(args);desiredPlay=args.getBoolean("play",true);
            if(useNative) {
                if(nativePlayer==null || generation!=nextGeneration || rate!=nextRate || nativePlayer.authorizationExpiring()) {
                    stopNative();
                    // Retire any browser before starting native audio, preserving one active transport.
                    Bundle stop=new Bundle(args);stop.putString("command","stop");SpotifyRemotePlayer.call(app,stop);
                    SpotifyPlaybackService.awaitForeground(app);
                    nativePlayer=new SpotifyNativePlayer(app,nextRate);generation=nextGeneration;rate=nextRate;
                }
                nativePlayer.load(args.getString("track_id"),desiredPlay);
            } else {stopNative();SpotifyPlaybackService.awaitForeground(app);SpotifyRemotePlayer.call(app,args);}
        } else if(command.equals("stop")) {stopAll();return "{\"state\":\"idle\",\"position_ms\":0}";}
        else if(nativePlayer!=null) {
            if(command.equals("play")) desiredPlay=true;
            else if(command.equals("pause")) desiredPlay=false;
            if(!command.equals("status")) nativePlayer.command(args);
        } else return browserStatus(SpotifyRemotePlayer.call(app,args));
        if(nativePlayer==null) return browserStatus(SpotifyRemotePlayer.call(app,statusArgs()));
        JSONObject status=nativePlayer.status();
        if(status.has("error") && !status.isNull("error")) {
            if(status.optBoolean("account_changed")) {stopAll();throw new IllegalStateException("Spotify account changed; start playback again");}
            String reason=status.getString("error");nativeFailure=reason;failedGeneration=generation;failedUntil=System.currentTimeMillis()+300000;
            app.getSharedPreferences("spotify_session",0).edit().putString("native_status",reason + ". Using web playback.").apply();
            stopNative();Bundle fallback=new Bundle(lastLoad);fallback.putBoolean("play",desiredPlay);
            SpotifyPlaybackService.awaitForeground(app);
            SpotifyRemotePlayer.call(app,fallback);
            return browserStatus(SpotifyRemotePlayer.call(app,statusArgs()));
        }
        String summary=status.optString("state","buffering") + " · requested " + rate + " kbps";
        if(status.optInt("bitrate_kbps")>0)summary+=" · playing " + status.optInt("bitrate_kbps") + " kbps";
        if(!summary.equals(app.getSharedPreferences("spotify_session",0).getString("native_status","")))
            app.getSharedPreferences("spotify_session",0).edit().putString("native_status",summary).apply();
        return observe(status.toString());
    }
    private static String browserStatus(String value) throws Exception {return observe(nativeFailure==null?value:new JSONObject(value).put("native_error",nativeFailure).toString());}
    private static Bundle statusArgs(){Bundle args=new Bundle();args.putString("command","status");args.putBinder("owner",owner);return args;}
    private static void stopNative(){if(nativePlayer!=null){nativePlayer.close();nativePlayer=null;}}
    private static void unlink(){if(owner!=null && death!=null)owner.unlinkToDeath(death,0);death=null;}
    private static void stopAll(){stopNative();if(context!=null && owner!=null)try{Bundle args=statusArgs();args.putString("command","stop");SpotifyRemotePlayer.call(context,args);}catch(Exception ignored){}
        if(context!=null)SpotifyPlaybackService.requestStop(context);
        unlink();owner=null;lastLoad=null;}
    static void pauseForNoise(){CLEANUP.execute(()->{synchronized(SpotifyPlaybackBridge.class){if(context!=null&&owner!=null)try{Bundle args=statusArgs();args.putString("command","pause");call(context,args);}catch(Exception ignored){}}});}
    static String diagnostics(Context context){return "Native audio: " + (SpotifyNativeEngine.AVAILABLE?"bundled":"not available for this build/ABI") + "\nNative state: " + context.getSharedPreferences("spotify_session",0).getString("native_status","not tried") + "\n" + SpotifyRemotePlayer.diagnostics(context);}
    static synchronized void stopForSignOut(){stopAll();}
    static synchronized void retryNative(){failedUntil=0;failedGeneration=-1;}
}
