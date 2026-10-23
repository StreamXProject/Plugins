package com.music.streamx.extensions.example;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Version 1 example. Service authorization and credentials belong to this APK. */
public final class SourceProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private void requireHost() {
        if (Binder.getCallingUid() == android.os.Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String name : packages) {
            if (name.equals("com.music.streamx") || name.equals("com.dev.streamx") || name.equals("com.music.streamx.benchmark") || name.equals("com.dev.streamx.benchmark")) return;
        }
        throw new SecurityException("Unknown StreamX host");
        // A production extension should also verify the allowed host signing certificates.
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireHost();
        String json;
        switch (method) {
            case "streamx.health.v1": json = "{\"ok\":true,\"message\":\"Example provider; no service configured\"}"; break;
            case "streamx.search.v1": json = "{\"items\":[]}"; break;
            case "streamx.stream.v1": json = "{\"url\":\"\"}"; break;
            default: json = "{\"error\":\"Unsupported method\"}";
        }
        Bundle response = new Bundle();
        response.putString("json", json);
        return response;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
