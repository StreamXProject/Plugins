package com.music.streamx.extensions.spotify;

/** Counts advancing, audible samples, never loading time, pauses or seek jumps. One event per load. */
final class SpotifyListenGate {
    private long lastTime=-1,lastPosition,heard;
    private boolean counted;
    boolean sample(boolean playing,long position,long now) {
        if(counted)return false;
        if(playing && lastTime>=0) {
            long elapsed=now-lastTime,advance=position-lastPosition;
            if(elapsed>0 && elapsed<=2000 && advance>0 && advance<=elapsed+1500)heard+=Math.min(elapsed,advance);
        }
        lastTime=playing?now:-1;lastPosition=position;
        if(heard<5000)return false;
        counted=true;return true;
    }
}
