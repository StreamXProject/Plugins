package com.music.streamx.extensions.spotify;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** API 1 provider: verified account, metadata feeds, search and browsed collections. */
public final class SpotifyProvider extends ContentProvider {
    private SpotifyApi api;
    @Override public boolean onCreate() { api = new SpotifyApi(getContext()); return true; }
    private void requireHost() {
        if (Binder.getCallingUid() == android.os.Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String name : packages) {
            if ((name.equals("com.music.streamx") || name.equals("com.dev.streamx") || name.equals("com.music.streamx.benchmark") || name.equals("com.dev.streamx.benchmark")) &&
                getContext().getPackageManager().checkSignatures(name, getContext().getPackageName()) == PackageManager.SIGNATURE_MATCH) return;
        }
        throw new SecurityException("Untrusted StreamX host");
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireHost();
        String json;
        try {
            if (extras == null) extras = new Bundle();
            switch (method) {
                case "streamx.health.v1":
                    org.json.JSONObject user = api.knownAccount();
                    json = new org.json.JSONObject().put("ok", true).put("message", "Connected as " + user.optString("name"))
                        .put("capabilities", new org.json.JSONArray(new String[]{"home","browse","library","search","detail","account","playback","saved","save","playlists","create_playlist","add_to_playlist","history"})).toString(); break;
                case "streamx.account.v1": json = api.account().toString(); break;
                case "streamx.plan.v1": json = api.plan(extras != null && extras.getBoolean("refresh",false)).toString(); break;
                case "streamx.playback_route.v1": json = api.playbackRoute().toString(); break;
                case "streamx.home.v1": json = api.feed("home").toString(); break;
                case "streamx.browse.v1": json = api.feed("browse").toString(); break;
                case "streamx.library.v1": json = api.feed("library").toString(); break;
                case "streamx.search.v1": json = api.search(extras.getString("query", ""), Math.max(1, Math.min(40, extras.getInt("limit", 10)))).toString(); break;
                case "streamx.detail.v1": json = api.detail(extras.getString("kind", ""), extras.getString("id", ""), Math.max(0, extras.getInt("offset", 0))).toString(); break;
                case "streamx.preferences.v1":
                case "streamx.set_preferences.v1": {
                    android.content.SharedPreferences state=getContext().getSharedPreferences("spotify_session",0);
                    if(method.equals("streamx.set_preferences.v1")) {
                        if(extras.containsKey("native_kbps") && !java.util.Set.of(96,160,320).contains(extras.getInt("native_kbps")))throw new IllegalArgumentException("Unsupported Spotify bitrate");
                        android.content.SharedPreferences.Editor edit=state.edit();
                        if(extras.containsKey("native_enabled"))edit.putBoolean("native_enabled",extras.getBoolean("native_enabled"));
                        if(extras.containsKey("native_kbps"))edit.putInt("native_kbps",extras.getInt("native_kbps"));
                        edit.apply();SpotifyPlaybackBridge.retryNative();
                    }
                    json=new org.json.JSONObject().put("native_available",SpotifyNativeEngine.AVAILABLE).put("native_enabled",state.getBoolean("native_enabled",false))
                        .put("native_kbps",state.getInt("native_kbps",320)).put("native_status",state.getString("native_status","Not tried yet"))
                        .put("connected",state.getBoolean("confirmed",false)&&state.getBoolean("verified",false)).put("name",state.getString("name","")).toString();break;
                }
                case "streamx.sign_out.v1": SpotifyPlaybackBridge.stopForSignOut();SpotifySession.signOut(getContext());json="{\"ok\":true}";break;
                case "streamx.playlists.v1": json=api.editablePlaylists(Math.max(0,extras.getInt("offset",0)),extras.getString("folder"),extras.getString("track")).toString();break;
                case "streamx.history.v1": json=api.history().toString();break;
                case "streamx.create_playlist.v1": json=api.createPlaylist(extras.getString("title")).toString();break;
                case "streamx.add_to_playlist.v1": json=api.addToPlaylist(extras.getString("id"),new org.json.JSONArray(extras.getString("tracks","[]"))).toString();break;
                case "streamx.saved.v1": json = api.saved(extras.getString("kind"), extras.getString("id")).toString(); break;
                case "streamx.save.v1": json = api.save(extras.getString("kind"), extras.getString("id"), extras.getBoolean("saved")).toString(); break;
                case "streamx.playback.v1": {
                    // Recheck at load as the account may have changed since the host's preflight.
                    if ("load".equals(extras.getString("command"))) {
                        org.json.JSONObject route=api.playbackRoute();
                        if (!"spotify".equals(route.optString("backend"))) {
                            SpotifyPlaybackBridge.stopForSignOut();
                            SpotifyPlaybackService.requestStop(getContext());
                            json=route.put("state","idle").toString();break;
                        }
                    }
                    json = SpotifyPlaybackBridge.call(getContext(), extras); break;
                }
                case "streamx.download.v1": json = SpotifyNativeDownload.describe(getContext(), extras.getString("track_id")).toString(); break;
                case "streamx.stream.v1": json = "{\"url\":\"\"}"; break;
                default: throw new IllegalArgumentException("This extension does not support that operation");
            }
            if (json.length() > 262144) throw new IllegalStateException("Spotify collection exceeded the extension response limit");
        } catch (Exception failure) {
            try { json = new org.json.JSONObject().put("error", failure.getMessage() == null ? "Spotify request failed" : failure.getMessage()).toString(); }
            catch (Exception impossible) { json = "{\"error\":\"Spotify request failed\"}"; }
        }
        Bundle result = new Bundle(); result.putString("json", json); return result;
    }

    @Override public android.os.ParcelFileDescriptor openFile(Uri uri,String mode) throws java.io.FileNotFoundException {
        requireHost();
        if (!"r".equals(mode)) throw new java.io.FileNotFoundException("Read-only audio");
        return SpotifyNativeDownload.open(getContext(), uri);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
