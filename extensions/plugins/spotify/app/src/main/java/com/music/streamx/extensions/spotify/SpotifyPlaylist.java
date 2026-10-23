package com.music.streamx.extensions.spotify;

import org.json.JSONObject;

/** Playlist identity and its paged contents are separate web-player operations. */
final class SpotifyPlaylist {
    static JSONObject parse(JSONObject metadata, JSONObject contents, String id, int offset) throws Exception {
        if (!id.matches("[A-Za-z0-9]{22}")) throw new IllegalArgumentException("Invalid Spotify playlist ID");
        JSONObject info = SpotifyGraph.at(metadata, "data", "playlistV2");
        if (info == null) throw new IllegalStateException("Spotify did not return the playlist metadata");
        String uri = "spotify:playlist:" + id;
        if (!info.optString("uri", "").isEmpty() && !uri.equals(info.optString("uri")))
            throw new IllegalStateException("Spotify returned a different playlist");
        // Some metadata selections omit uri; the validated requested identity is authoritative.
        info = new JSONObject(info.toString()).put("uri", uri);
        JSONObject card = SpotifyGraph.item(info, null);
        if (card == null) throw new IllegalStateException("Spotify did not return the playlist name");
        JSONObject playlist = SpotifyGraph.at(contents, "data", "playlistV2");
        if (playlist != null && !playlist.optString("uri", "").isEmpty() && !uri.equals(playlist.optString("uri")))
            throw new IllegalStateException("Spotify returned tracks from a different playlist");
        JSONObject page = SpotifyGraph.at(playlist, "content");
        if (page == null || page.optJSONArray("items") == null)
            throw new IllegalStateException("Spotify did not return the playlist tracks");
        return new JSONObject().put("title", card.optString("title")).put("subtitle", card.optString("subtitle"))
            .put("thumbnail_url", card.optString("thumbnail_url")).put("kind", "playlist")
            .put("items", SpotifyGraph.items(page, null, 25)).put("next_offset", SpotifyGraph.next(page, offset));
    }
}
