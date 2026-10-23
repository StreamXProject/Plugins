package com.music.streamx.extensions.spotify;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/** Librespot's confirmed decoded 44.1 kHz stereo PCM, played privately through Android AudioTrack. */
public final class SpotifyNativeAudio {
    private final AudioManager manager;
    private final AudioFocusRequest focus;
    private volatile AudioTrack output;
    private volatile boolean retired,paused=true,requested;
    SpotifyNativeAudio(Context context) {
        manager=context.getSystemService(AudioManager.class);
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> {
                if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    SpotifyPlaybackBridge.pauseForNoise();
                else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {AudioTrack track=output;if(track!=null) track.setVolume(.2f);}
                else if(change==AudioManager.AUDIOFOCUS_GAIN) {AudioTrack track=output;if(track!=null) track.setVolume(1f);}
            },new Handler(Looper.getMainLooper())).build();
    }
    void requested(boolean play) {requested=play;}
    public synchronized void start() {
        if(retired || !requested) return;
        if(output==null) {
            int minimum=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            output=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum,8820)).setTransferMode(AudioTrack.MODE_STREAM).build();
        }
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("Audio focus unavailable");
        paused=false; output.play();
    }
    public void pause() {
        paused=true; AudioTrack track=output;
        if(track!=null) try {track.pause();} catch(IllegalStateException ignored) { }
    }
    void reset() {pause();AudioTrack track=output;if(track!=null) try {track.flush();} catch(IllegalStateException ignored) { }}
    public int write(short[] samples) {
        AudioTrack track=output;
        if(retired || !requested || paused || track==null) return 0;
        int written=0;
        while(written<samples.length && !retired && requested && !paused) {
            int count;
            try {count=track.write(samples,written,samples.length-written,AudioTrack.WRITE_NON_BLOCKING);}
            catch(IllegalStateException stopped) {return retired?written:-1;}
            if(count<0) return count;
            written+=count;
            if(count==0) try {Thread.sleep(2);} catch(InterruptedException stop) {Thread.currentThread().interrupt();return written;}
        }
        return written;
    }
    synchronized void retire() {
        retired=true; requested=false; pause(); AudioTrack track=output; output=null;
        if(track!=null) track.release(); manager.abandonAudioFocusRequest(focus);
    }
}
