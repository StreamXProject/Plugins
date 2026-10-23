package com.music.streamx.extensions.spotify;

/** JNI declarations. Tokens remain inside the signed extension process and never enter IPC replies. */
final class SpotifyNativeEngine {
    static final boolean AVAILABLE;
    static {
        boolean available;
        try { System.loadLibrary("streamx_spotify_native"); available=true; }
        catch(LinkageError | SecurityException unsupported) { available=false; }
        AVAILABLE=available;
    }
    static native void download(String accessToken,String clientToken,long lifetimeSeconds,String user,String cacheDirectory,String track,int bitrate,int outputFd) throws java.io.IOException;
    static native long create(String accessToken,String clientToken,long lifetimeSeconds,String user,String cacheDirectory,int bitrate,SpotifyNativeAudio output);
    static native void command(long handle,int command,String trackId,long positionMs,int play);
    static native String status(long handle);
    static native void close(long handle);
}
