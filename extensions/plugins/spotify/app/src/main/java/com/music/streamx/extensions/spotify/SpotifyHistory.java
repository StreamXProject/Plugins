package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;

/** Converts Spotify's recently-played REST tracks without mixing in another account/source. */
final class SpotifyHistory {
    static JSONObject recents(JSONObject response) throws Exception {
        JSONArray lists=SpotifyGraph.at(response,"data")==null?null:response.getJSONObject("data").optJSONArray("lists");
        JSONObject container=lists==null?null:lists.optJSONObject(0);
        JSONObject page=SpotifyGraph.at(container,"items");
        JSONArray rows=page==null?null:page.optJSONArray("items");
        if(rows==null)throw new IllegalStateException("Spotify did not return its recents list");
        JSONArray tracks=new JSONArray(),cards=new JSONArray();
        for(int n=0;n<Math.min(rows.length(),50);n++) {
            JSONObject row=rows.optJSONObject(n),wrapper=SpotifyGraph.at(row,"entity"),entity=SpotifyGraph.at(wrapper,"data");
            if(entity==null)continue;
            String uri=wrapper.optString("_uri",entity.optString("uri",""));
            String kind=uri.startsWith("spotify:track:")?"track":uri.startsWith("spotify:playlist:")?"playlist":uri.startsWith("spotify:album:")?"album":uri.startsWith("spotify:artist:")?"artist":"";
            String id=kind.isEmpty()?"":uri.substring(("spotify:"+kind+":").length());
            if(!id.matches("[A-Za-z0-9]{22}"))continue;
            JSONObject identity=SpotifyGraph.at(entity,"identityTrait"),visual=SpotifyGraph.at(entity,"visualIdentityTrait"),experience=SpotifyGraph.at(entity,"consumptionExperienceTrait");
            if(identity==null || identity.optString("name").isBlank())continue;
            StringBuilder names=new StringBuilder();
            JSONArray contributors=SpotifyGraph.at(identity,"contributors")==null?null:identity.getJSONObject("contributors").optJSONArray("items");
            if(contributors!=null)for(int c=0;c<contributors.length();c++) {
                JSONObject contributor=contributors.optJSONObject(c);String name=contributor==null?"":contributor.optString("name");
                if(!name.isBlank()){if(names.length()>0)names.append(" / ");names.append(name);}
            }
            String image=SpotifyGraph.art(SpotifyGraph.at(visual,"squareCoverImage","image","data"));
            if(image.isEmpty()) {
                JSONArray originals=SpotifyGraph.at(visual,"squareCoverImage")==null?null:visual.getJSONObject("squareCoverImage").optJSONArray("originalInstances");
                if(originals!=null)for(int i=0;i<originals.length();i++) {JSONObject flat=SpotifyGraph.at(originals.optJSONObject(i),"flatFile");String url=flat==null?"":flat.optString("cdnUrl");if(url.startsWith("https://")){image=url;break;}}
            }
            JSONObject card=new JSONObject().put("id",id).put("type",kind).put("title",identity.optString("name")).put("subtitle",names.toString()).put("thumbnail_url",image);
            if(kind.equals("track")) {
                JSONObject duration=SpotifyGraph.at(experience,"duration"),parent=SpotifyGraph.at(identity,"contentHierarchyParent");
                card.put("artist",names.toString()).put("duration_sec",duration==null?0:Math.max(0,duration.optLong("seconds")))
                    .put("album",parent==null?"":parent.optString("name"));
                tracks.put(card);
            } else cards.put(card);
        }
        JSONArray sections=new JSONArray();
        if(cards.length()>0)sections.put(new JSONObject().put("title","Recently played collections").put("items",cards));
        return new JSONObject().put("items",tracks).put("sections",sections);
    }
    static JSONObject parse(JSONObject response) throws Exception {
        JSONArray rows = response.optJSONArray("items"), out = new JSONArray();
        if (rows == null) throw new IllegalStateException("Spotify did not return listening history");
        for (int i = 0; i < Math.min(rows.length(), 50); i++) {
            JSONObject track = SpotifyGraph.at(rows.optJSONObject(i), "track");
            String id = SpotifyGraph.id(track, "track");
            if (id.isEmpty() || !"track".equals(track.optString("type", "track"))) continue;
            JSONArray artists = track.optJSONArray("artists");
            StringBuilder credit = new StringBuilder(); String artistId = "";
            if (artists != null) for (int a = 0; a < artists.length(); a++) {
                JSONObject artist = artists.optJSONObject(a); if (artist == null) continue;
                if (artistId.isEmpty()) artistId = SpotifyGraph.id(artist, "artist");
                String name = artist.optString("name");
                if (!name.isBlank()) { if (credit.length() > 0) credit.append(" / "); credit.append(name); }
            }
            JSONObject album = track.optJSONObject("album"); String image = "";
            JSONArray images = album == null ? null : album.optJSONArray("images");
            if (images != null) for (int n = 0; n < images.length(); n++) {
                JSONObject entry = images.optJSONObject(n); String url = entry == null ? "" : entry.optString("url");
                if (url.startsWith("https://")) { image = url; break; }
            }
            long playedAt=0;
            try {playedAt=java.time.Instant.parse(rows.getJSONObject(i).optString("played_at")).toEpochMilli();}catch(Exception ignored){}
            out.put(new JSONObject().put("played_at_ms",playedAt).put("id", id).put("type", "track").put("title", track.optString("name"))
                .put("artist", credit.toString()).put("artist_id", artistId).put("subtitle", credit.toString())
                .put("album", album == null ? "" : album.optString("name")).put("album_id", SpotifyGraph.id(album, "album"))
                .put("thumbnail_url", image).put("duration_sec", Math.max(0, track.optLong("duration_ms") / 1000))
                .put("explicit", track.optBoolean("explicit")));
        }
        // Preserve newest-first order and repeated listens; history is a sequence of events.
        return new JSONObject().put("items", out);
    }
}
