package com.music.streamx.extensions.spotify;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;

/** Signed-host-only native transfer; credentials never leave the plugin. */
final class SpotifyNativeDownload {
    private static final java.util.concurrent.ExecutorService TRANSFERS = new java.util.concurrent.ThreadPoolExecutor(
        0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
    static String track(String value) {
        String id = value != null && value.startsWith("spotify:track:") ? value.substring(14) : value;
        if (id == null || !id.matches("[A-Za-z0-9]{22}")) throw new IllegalArgumentException("Invalid Spotify track");
        return id;
    }
    static JSONObject describe(Context context, String value) throws Exception {
        if (!SpotifyNativeEngine.AVAILABLE || !context.getSharedPreferences("spotify_session",0).getBoolean("native_enabled",false))
            throw new IllegalStateException("Enable native Spotify audio first");
        if (!"spotify".equals(new SpotifyApi(context).playbackRoute().optString("backend")))
            throw new IllegalStateException("Native downloads require verified Spotify Premium");
        int rate = context.getSharedPreferences("spotify_session",0).getInt("native_kbps",320);
        return new JSONObject().put("url",new Uri.Builder().scheme("content").authority(context.getPackageName()+".source")
            .appendPath("download").appendPath(track(value)).appendQueryParameter("kbps",Integer.toString(rate)).build().toString())
            .put("codec","vorbis").put("kbps",rate);
    }
    static ParcelFileDescriptor open(Context context, Uri uri) throws java.io.FileNotFoundException {
        try {
            if (uri.getPathSegments().size()!=2 || !"download".equals(uri.getPathSegments().get(0))) throw new IllegalArgumentException("Invalid download path");
            String id = track(uri.getLastPathSegment());
            int rate = Integer.parseInt(uri.getQueryParameter("kbps"));
            if (rate!=96 && rate!=160 && rate!=320) throw new IllegalArgumentException("Invalid bitrate");
            describe(context,id);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            try { TRANSFERS.execute(() -> {
                try {
                    SpotifyApi api = new SpotifyApi(context);
                    String user = api.account().getString("id").substring("spotify:user:".length());
                    SpotifySession.Credentials credentials = SpotifySession.obtain(context);
                    java.io.File cache = new java.io.File(context.getCacheDir(),"native_download");
                    if (!cache.exists() && !cache.mkdirs()) throw new java.io.IOException("Native cache unavailable");
                    SpotifyNativeEngine.download(credentials.bearer,credentials.client,Math.max(0,(credentials.nativeExpires-System.currentTimeMillis())/1000),user,
                        cache.getAbsolutePath(),id,rate,pipe[1].getFd());
                } catch (Exception | LinkageError failure) { try { pipe[1].closeWithError("Native Spotify download failed. Check your account and retry."); } catch (Exception ignored) {} }
                finally { try { pipe[1].close(); } catch (Exception ignored) {} }
            }); } catch (RuntimeException failure) { pipe[0].close();pipe[1].close();throw failure; }
            return pipe[0];
        } catch (Exception failure) { throw new java.io.FileNotFoundException(failure.getMessage()); }
    }
}
