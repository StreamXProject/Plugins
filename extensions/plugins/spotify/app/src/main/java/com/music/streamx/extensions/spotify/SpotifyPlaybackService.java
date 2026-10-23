package com.music.streamx.extensions.spotify;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the private renderer eligible for background media playback and audio focus. */
public final class SpotifyPlaybackService extends Service {
    static final String STOP="streamx.playback.STOP";
    static void requestStop(android.content.Context context) {
        // A queued start must reach startForeground before any stop is processed.
        context.startService(new Intent(context,SpotifyPlaybackService.class).setAction(STOP));
    }
    private static final String READY="spotify.playback.ready";
    /** Called on the provider thread, before any expensive renderer initialization. */
    static void awaitForeground(android.content.Context context) throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
            throw new IllegalStateException("Playback startup must run off the main thread");
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        android.os.ResultReceiver receiver=new android.os.ResultReceiver(null) {
            @Override protected void onReceiveResult(int code,android.os.Bundle data) {ready.countDown();}
        };
        context.startForegroundService(new Intent(context,SpotifyPlaybackService.class).putExtra(READY,receiver));
        if(!ready.await(5,java.util.concurrent.TimeUnit.SECONDS))
            throw new IllegalStateException("Spotify playback service did not become ready");
    }
    private final android.content.BroadcastReceiver noisy = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) { SpotifyPlaybackBridge.pauseForNoise(); }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("spotify_browser", "Spotify playback", NotificationManager.IMPORTANCE_LOW));
        promote();
        android.content.IntentFilter filter=new android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(noisy,filter,android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy,filter);
    }
    private void promote() {
        startForeground(72, new Notification.Builder(this, "spotify_browser").setSmallIcon(R.drawable.ic_spotify)
            .setContentTitle("Spotify playback").setContentText("Controlled by StreamX’s player").setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        // Each startForegroundService request must be acknowledged, including rapid restarts.
        promote();
        if(intent!=null && STOP.equals(intent.getAction())) { stopSelfResult(id); return START_NOT_STICKY; }
        android.os.ResultReceiver ready=intent==null?null:android.os.Build.VERSION.SDK_INT>=33
            ?intent.getParcelableExtra(READY,android.os.ResultReceiver.class):intent.getParcelableExtra(READY);
        if(ready!=null)ready.send(0,android.os.Bundle.EMPTY);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { unregisterReceiver(noisy); super.onDestroy(); }
}
