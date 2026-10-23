package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;

/** Validation and wire payloads for explicit playlist writes; never manufacture catalogue IDs. */
final class SpotifyPlaylistWrite {
    static String uri(String kind,String id) {
        if(id==null || !id.matches("[A-Za-z0-9]{22}"))throw new IllegalArgumentException("Invalid Spotify "+kind+" ID");
        return "spotify:"+kind+":"+id;
    }
    static JSONObject create(String title) throws Exception {
        String name=title==null?"":title.trim();
        if(name.isEmpty() || name.length()>300)throw new IllegalArgumentException("Enter a playlist name of up to 300 characters");
        JSONObject values=new JSONObject().put("name",name).put("description","").put("formatAttributes",new JSONArray()).put("pictureSize",new JSONArray());
        JSONObject attributes=new JSONObject().put("values",values).put("noValue",new JSONArray());
        return new JSONObject().put("ops",new JSONArray().put(new JSONObject().put("kind",6).put("updateListAttributes",new JSONObject().put("newAttributes",attributes))));
    }
    static JSONObject attachToLibrary(String id, long timestamp) throws Exception {
        JSONObject item = new JSONObject().put("uri", uri("playlist", id))
            .put("attributes", new JSONObject().put("timestamp", timestamp)
                .put("formatAttributes", new JSONArray()).put("availableSignals", new JSONArray()));
        JSONObject op = new JSONObject().put("kind", 2).put("add", new JSONObject()
            .put("items", new JSONArray().put(item)).put("addFirst", true));
        JSONObject delta = new JSONObject().put("ops", new JSONArray().put(op))
            .put("info", new JSONObject().put("source", new JSONObject().put("client", 5)));
        return new JSONObject().put("deltas", new JSONArray().put(delta));
    }
    static JSONObject editableVariables(int offset, String folder, String track) throws Exception {
        if (folder != null && !folder.matches("spotify:folder:[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid Spotify playlist folder");
        JSONArray uris = new JSONArray();
        if (track != null) uris.put(uri("track", track));
        return new JSONObject().put("textFilter", "").put("limit", 50).put("offset", Math.max(0, offset))
            .put("folderUri", folder == null ? JSONObject.NULL : folder).put("uris", uris);
    }
    static JSONObject editablePage(JSONObject page, int offset) throws Exception {
        JSONArray raw = page.optJSONArray("items"), items = new JSONArray(), folders = new JSONArray();
        if (raw == null) {
            throw new IllegalStateException(page.optString("message", "Spotify did not return editable playlist items"));
        }
        for (int i = 0; i < raw.length(); i++) {
            JSONObject value = SpotifyGraph.unwrap(raw.optJSONObject(i));
            if (value == null) continue;
            String folder = value.optString("uri");
            if (folder.matches("spotify:folder:[A-Za-z0-9_-]{1,128}")) { folders.put(folder); continue; }
            JSONObject card = SpotifyGraph.item(value, null);
            if (card != null && "playlist".equals(card.optString("type"))) items.put(card);
        }
        return new JSONObject().put("items", items).put("folders", folders).put("next_offset", SpotifyGraph.next(page, offset));
    }
    /** Conservative library fallback when Spotify's add-track picker rejects an empty URI list. */
    static JSONObject ownedLibraryPage(JSONObject page,int offset,String account) throws Exception {
        JSONArray rows=page.optJSONArray("items"),items=new JSONArray();
        if(rows==null)throw new IllegalStateException("Spotify did not return library items");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i),value=SpotifyGraph.unwrap(row);
            if(row==null || value==null)continue;
            JSONObject owner=SpotifyGraph.at(value,"ownerV2","data");
            boolean owned=account.startsWith("spotify:user:") && owner!=null && account.equals(owner.optString("uri"));
            if(!owned && !row.optBoolean("curates",false))continue;
            JSONObject card=SpotifyGraph.item(value,null);
            if(card!=null && "playlist".equals(card.optString("type")))items.put(card);
        }
        return new JSONObject().put("items",items).put("folders",new JSONArray()).put("next_offset",SpotifyGraph.next(page,offset));
    }
    static JSONObject add(String id,JSONArray tracks) throws Exception {
        String playlist=uri("playlist",id);JSONArray uris=new JSONArray();
        if(tracks==null || tracks.length()==0 || tracks.length()>100)throw new IllegalArgumentException("Choose between 1 and 100 tracks");
        for(int i=0;i<tracks.length();i++)uris.put(uri("track",tracks.getString(i)));
        return new JSONObject().put("playlistUri",playlist).put("uris",uris)
            .put("newPosition",new JSONObject().put("moveType","BOTTOM_OF_PLAYLIST").put("fromUid",JSONObject.NULL));
    }
    static String createdId(JSONObject response) {
        String uri=response.optString("uri");
        if(!uri.matches("spotify:playlist:[A-Za-z0-9]{22}"))throw new IllegalStateException("Spotify did not return the created playlist ID; refresh your library before retrying");
        return uri.substring("spotify:playlist:".length());
    }
}
