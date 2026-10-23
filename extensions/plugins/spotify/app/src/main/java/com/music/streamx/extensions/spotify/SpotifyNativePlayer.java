package com.music.streamx.extensions.spotify;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.Executors;

/** One native session, with StreamX retaining all queue/autoplay decisions. */
final class SpotifyNativePlayer {
    private static final java.util.concurrent.ExecutorService INIT=Executors.newSingleThreadExecutor();
    private final Context context;
    private final SpotifyNativeAudio audio;
    private final int bitrate;
    private final long accountGeneration;
    private volatile long handle;
    private volatile long authorizationExpires=Long.MAX_VALUE;
    private volatile boolean closed;
    private volatile String failure;
    private String track;
    private boolean wanted;
    private long started;
    private long pendingSeek=-1;
    private long commandSequence,commandLatency;
    private String commandName="load";
    SpotifyNativePlayer(Context context,int bitrate) {
        this.context=context.getApplicationContext();this.bitrate=bitrate;audio=new SpotifyNativeAudio(context);
        accountGeneration=context.getSharedPreferences("spotify_session",0).getLong("generation",0);
        INIT.execute(() -> {
            try {
                JSONObject user=new SpotifyApi(this.context).account();
                SpotifySession.Credentials credentials=SpotifySession.obtain(this.context);
                String id=user.getString("id").substring("spotify:user:".length());
                File cache=new File(this.context.getCacheDir(),"native_audio");
                if(!cache.exists() && !cache.mkdirs()) throw new IllegalStateException("Native cache unavailable");
                synchronized(this) {
                    if(closed || accountGeneration!=this.context.getSharedPreferences("spotify_session",0).getLong("generation",0)) return;
                    authorizationExpires=credentials.nativeExpires;
                    handle=SpotifyNativeEngine.create(credentials.bearer,credentials.client,Math.max(0,(authorizationExpires-System.currentTimeMillis())/1000),id,cache.getAbsolutePath(),bitrate,audio);
                    if(track!=null) SpotifyNativeEngine.command(handle,0,track,0,wanted?1:0);
                    if(pendingSeek>=0) SpotifyNativeEngine.command(handle,3,"",pendingSeek,0);
                }
            } catch(Exception | LinkageError error) {failure="Native initialization failed; web playback is available";}
        });
    }
    boolean authorizationExpiring(){return System.currentTimeMillis()+45000>=authorizationExpires;}
    synchronized void load(String id,boolean play) {
        if(SpotifyPlayback.track(id)==null) throw new IllegalArgumentException("Invalid Spotify track ID");
        audio.requested(play);audio.reset(); track=id;wanted=play;started=System.currentTimeMillis();pendingSeek=-1;if(handle!=0)failure=null;
        if(handle!=0) SpotifyNativeEngine.command(handle,0,id,0,play?1:0);
    }
    synchronized void command(Bundle args) {
        String command=args.getString("command","");long queued=args.getLong("queued_at",android.os.SystemClock.elapsedRealtime());
        if(command.equals("play")) {wanted=true;started=System.currentTimeMillis();audio.requested(true);if(handle!=0) SpotifyNativeEngine.command(handle,1,"",0,1);}
        else if(command.equals("pause")) {wanted=false;audio.requested(false);if(handle!=0) SpotifyNativeEngine.command(handle,2,"",0,0);audio.pause();}
        else if(command.equals("seek")) {
            // Librespot keeps the sink running during seek. Pausing AudioTrack here would
            // silently discard decoded packets; retain its bounded ~50 ms output buffer.
            pendingSeek=Math.max(0,args.getLong("position_ms"));
            if(handle!=0) SpotifyNativeEngine.command(handle,3,"",pendingSeek,0);
        }
        commandName=command;commandSequence++;commandLatency=Math.max(0,android.os.SystemClock.elapsedRealtime()-queued);
    }
    synchronized JSONObject status() throws Exception {
        JSONObject value=handle==0?new JSONObject():new JSONObject(SpotifyNativeEngine.status(handle));
        boolean accountChanged=accountGeneration!=context.getSharedPreferences("spotify_session",0).getLong("generation",0);
        value=SpotifyNativePolicy.status(value,track,wanted,System.currentTimeMillis()-started,accountChanged,failure);
        return value.put("backend","native").put("track_id",track).put("position_ms",value.optLong("position_ms",0))
            .put("duration_ms",value.optLong("duration_ms",0)).put("requested_kbps",bitrate)
            .put("play_control",handle==0?"native authentication":"native decoder")
            .put("startup_ms",System.currentTimeMillis()-started).put("control_name",commandName)
            .put("control_sequence",commandSequence).put("control_latency_ms",commandLatency);
    }
    synchronized void close() {closed=true;audio.retire();if(handle!=0){SpotifyNativeEngine.close(handle);handle=0;}}
}
