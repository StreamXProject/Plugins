package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;

/** Shape used by the signed-in web player's profileAttributes query. */
final class SpotifyProfile {
    static JSONObject parse(JSONObject response) throws Exception {
        if (response.optJSONArray("errors") != null && response.optJSONArray("errors").length() > 0)
            throw new IllegalStateException("Spotify rejected the profile query. The web-player session or query may have changed.");
        JSONObject data = response.optJSONObject("data"), me = data == null ? null : data.optJSONObject("me");
        JSONObject profile = me == null ? null : me.optJSONObject("profile");
        String uri = profile == null ? "" : profile.optString("uri", "");
        if (!uri.startsWith("spotify:user:") || uri.length() <= 13) throw new IllegalStateException("Spotify did not return a signed-in profile");
        String name = profile.optString("name", "");
        if (name.isEmpty()) name = uri.substring(13);
        String cover = "";
        JSONObject avatar = profile.optJSONObject("avatar");
        JSONArray sources = avatar == null ? null : avatar.optJSONArray("sources");
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            String url = source == null ? "" : source.optString("url", "");
            if (url.startsWith("https://")) cover = url;
        }
        return new JSONObject().put("id", uri).put("name", name).put("thumbnail_url", cover);
    }
}
