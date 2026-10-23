package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;

/** Handles REST track wrappers and optional metadata without dropping a whole collection. */
final class SpotifyJson {
    static String cover(JSONObject object) {
        if (object == null) return "";
        JSONArray images = object.optJSONArray("images");
        if (images == null || images.length() == 0) return "";
        JSONObject first = images.optJSONObject(0);
        String url = first == null ? "" : first.optString("url");
        return url.startsWith("https://") ? url : "";
    }
    static String credit(JSONObject object) {
        if (object == null) return "";
        JSONArray artists = object.optJSONArray("artists"); StringBuilder text = new StringBuilder();
        if (artists != null) for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i); if (artist == null || artist.optString("name").isEmpty()) continue;
            if (text.length() > 0) text.append(" / "); text.append(artist.optString("name"));
        }
        JSONObject owner = object.optJSONObject("owner");
        return text.length() > 0 ? text.toString() : owner == null ? "" : owner.optString("display_name", owner.optString("id"));
    }
    static JSONObject track(JSONObject value) throws Exception { return track(value, null); }
    static JSONObject track(JSONObject value, JSONObject fallbackAlbum) throws Exception {
        if (value == null) return null;
        if (value.has("track")) value = value.optJSONObject("track");
        else if (value.has("item")) value = value.optJSONObject("item");
        if (value == null || (value.has("type") && !"track".equals(value.optString("type")))) return null;
        String id = value.optString("id"), title = value.optString("name");
        if (!id.matches("[A-Za-z0-9]{22}") || title.isEmpty()) return null;
        JSONObject album = value.optJSONObject("album"); if (album == null) album = fallbackAlbum;
        JSONArray artists = value.optJSONArray("artists"); JSONObject first = artists == null ? null : artists.optJSONObject(0);
        return new JSONObject().put("type", "track").put("id", id).put("title", title).put("artist", credit(value))
            .put("artist_id", first == null ? "" : first.optString("id")).put("album", album == null ? "" : album.optString("name"))
            .put("album_id", album == null ? "" : album.optString("id")).put("thumbnail_url", cover(album))
            .put("duration_sec", Math.max(0, value.optLong("duration_ms") / 1000)).put("explicit", value.optBoolean("explicit"));
    }
    static JSONArray tracks(JSONArray array) throws Exception { return tracks(array, null); }
    static JSONArray tracks(JSONArray array, JSONObject album) throws Exception {
        JSONArray items = new JSONArray();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject value = track(array.optJSONObject(i), album); if (value != null) items.put(value);
        }
        return items;
    }
    static JSONObject card(JSONObject object, String kind) throws Exception {
        if (object == null || !object.optString("id").matches("[A-Za-z0-9]{22}") || object.optString("name").isEmpty()) return null;
        return new JSONObject().put("type", kind).put("id", object.getString("id")).put("title", object.getString("name"))
            .put("subtitle", credit(object)).put("thumbnail_url", cover(object));
    }
}
