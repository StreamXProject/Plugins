package com.music.streamx.extensions.spotify;

import org.json.JSONArray;
import org.json.JSONObject;

/** Normalizes web-player entities into the host's existing metadata contract. */
final class SpotifyGraph {
    static JSONObject at(JSONObject root, String... keys) {
        for (String key : keys) { if (root == null) return null; root = root.optJSONObject(key); }
        return root;
    }
    static String id(JSONObject value, String kind) {
        if (value == null) return "";
        String uri = value.optString("uri", ""), prefix = "spotify:" + kind + ":";
        String id = uri.startsWith(prefix) ? uri.substring(prefix.length()) : value.optString("id", "");
        return id.matches("[A-Za-z0-9]{22}") ? id : "";
    }
    static String art(JSONObject value) {
        if (value == null) return "";
        JSONArray sources = value.optJSONArray("sources");
        if (sources != null) for (int i = sources.length() - 1; i >= 0; i--) {
            JSONObject image = sources.optJSONObject(i);
            String url = image == null ? "" : image.optString("url", "");
            if (url.startsWith("https://")) return url;
        }
        JSONArray items = value.optJSONArray("items");
        if (items != null && items.length() > 0) return art(items.optJSONObject(0));
        return "";
    }
    static String title(JSONObject value) {
        return value == null ? "" : value.optString("transformedLabel", value.optString("text", ""));
    }
    static JSONObject unwrap(JSONObject value) {
        String inheritedUri = "";
        for (int depth = 0; value != null && depth < 6; depth++) {
            String uri = value.optString("uri", value.optString("_uri", ""));
            if (!uri.isEmpty()) inheritedUri = uri;
            if (!value.optString("name", "").isEmpty() || value.optJSONObject("profile") != null) break;
            JSONObject inner = null;
            for (String key : new String[]{"data", "itemV2", "item", "content", "track"}) {
                inner = value.optJSONObject(key); if (inner != null) break;
            }
            if (inner == null) break;
            value = inner;
        }
        if (value != null && value.optString("uri", "").isEmpty() && !inheritedUri.isEmpty()) {
            try { return new JSONObject(value.toString()).put("uri", inheritedUri); }
            catch (Exception ignored) { return value; }
        }
        return value;
    }
    static String artists(JSONObject value) {
        JSONArray items = value == null ? null : value.optJSONArray("items");
        StringBuilder names = new StringBuilder();
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject artist = unwrap(items.optJSONObject(i));
            JSONObject profile = at(artist, "profile");
            String name = profile == null ? (artist == null ? "" : artist.optString("name", "")) : profile.optString("name", "");
            if (!name.isEmpty()) { if (names.length() > 0) names.append(" / "); names.append(name); }
        }
        return names.toString();
    }
    static JSONObject item(JSONObject value, JSONObject fallbackAlbum) throws Exception {
        value = unwrap(value); if (value == null) return null;
        String uri = value.optString("uri", "");
        String kind = uri.startsWith("spotify:track:") ? "track" : uri.startsWith("spotify:album:") ? "album"
            : uri.startsWith("spotify:artist:") ? "artist" : uri.startsWith("spotify:playlist:") ? "playlist" : "";
        if(kind.isEmpty() && uri.matches("spotify:(?:page|genre|hub|section):.{1,900}")) {
            JSONObject card=at(value,"cardRepresentation");if(card==null)card=at(value,"data","cardRepresentation");
            String label=title(at(card,"title"));if(label.isEmpty())label=value.optString("name","");
            if(!label.isEmpty())return new JSONObject().put("type","collection").put("id","browse:"+uri).put("title",label).put("thumbnail_url",art(at(card,"artwork"))).put("background_color",at(card,"backgroundColor")==null?JSONObject.NULL:at(card,"backgroundColor").optString("hex"));
        }
        String id = id(value, kind); if (kind.isEmpty() || id.isEmpty()) return null;
        String name = kind.equals("artist") ? (at(value, "profile") == null ? "" : at(value, "profile").optString("name", "")) : value.optString("name", "");
        if (name.isEmpty()) return null;
        String image = kind.equals("artist") ? art(at(value, "visuals", "avatarImage")) : art(at(value, kind.equals("playlist") ? "images" : "coverArt"));
        String credit = artists(at(value, "artists"));
        if (kind.equals("playlist")) { JSONObject owner = at(value, "ownerV2", "data"); credit = owner == null ? "" : owner.optString("name", owner.optString("displayName", "")); }
        if(kind.equals("playlist")) {
            String description=plainText(value.opt("description"));
            JSONObject followers=at(value,"followers");
            long count=followers==null?value.optLong("followersCount",-1):followers.optLong("totalCount",followers.optLong("count",-1));
            if(count<0 && value.opt("followers") instanceof Number)count=((Number)value.opt("followers")).longValue();
            String popularity=count<0?"":java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(count)+(count==1?" follower":" followers");
            if(!description.isEmpty())credit=description+(popularity.isEmpty()?"":"\n"+popularity);
            else if(!popularity.isEmpty())credit=popularity;
        }
        JSONObject out = new JSONObject().put("type", kind).put("id", id).put("title", name).put("subtitle", credit).put("thumbnail_url", image);
        if (kind.equals("track")) {
            JSONObject album = at(value, "albumOfTrack"); if (album == null) album = fallbackAlbum;
            JSONObject duration = at(value, "duration"); if (duration == null) duration = at(value, "trackDuration");
            JSONArray aa = at(value, "artists") == null ? null : at(value, "artists").optJSONArray("items");
            JSONObject first = aa == null ? null : unwrap(aa.optJSONObject(0));
            out.put("artist", credit).put("artist_id", id(first, "artist"))
                .put("album", album == null ? "" : album.optString("name", "")).put("album_id", id(album, "album"))
                .put("thumbnail_url", art(at(album, "coverArt")))
                .put("duration_sec", duration == null ? 0 : Math.max(0, duration.optLong("totalMilliseconds") / 1000))
                .put("explicit", "EXPLICIT".equals(at(value, "contentRating") == null ? "" : at(value, "contentRating").optString("label", "")));
        }
        return out;
    }
    static String plainText(Object value) {
        if(value==null || value==JSONObject.NULL)return "";
        String text=value instanceof JSONObject?title((JSONObject)value):value instanceof String?(String)value:"";
        return text.replaceAll("<[^>]*>", "").replace("&nbsp;"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").trim();
    }
    static JSONArray items(JSONObject container, JSONObject album, int limit) throws Exception {
        JSONArray raw = container == null ? null : container.optJSONArray("items"), out = new JSONArray();
        if (raw != null) for (int i = 0; i < Math.min(raw.length(), limit); i++) {
            JSONObject value = item(raw.optJSONObject(i), album);
            if (value != null) out.put(value);
        }
        return out;
    }
    static JSONObject home(JSONObject response) throws Exception {
        JSONObject home = at(response, "data", "home"), sections = at(home, "sectionContainer", "sections");
        if (home == null || sections == null) throw new IllegalStateException("Spotify did not return its home feed");
        JSONArray raw = sections.optJSONArray("items"), out = new JSONArray();
        if (raw != null) for (int i = 0; i < Math.min(30, raw.length()); i++) {
            JSONObject section = raw.optJSONObject(i); if (section == null) continue;
            JSONArray items = items(at(section, "sectionItems"), null, 20);
            String heading = title(at(section, "data", "title"));
            if (heading.isEmpty()) heading = title(at(home, "greeting"));
            if (heading.isEmpty()) heading = "For you";
            if (items.length() == 0) continue;
            JSONObject row = new JSONObject().put("title", heading).put("subtitle", title(at(section, "data", "subtitle"))).put("items", items);
            String uri = section.optString("uri", ""); if (!uri.isEmpty()) row.put("id", "home:" + uri);
            out.put(row);
        }
        if (out.length() == 0 && raw != null && raw.length() > 0) throw new IllegalStateException("Spotify home returned no supported music sections");
        return new JSONObject().put("sections", out);
    }
    static JSONObject browse(JSONObject response,boolean root,int offset) throws Exception {
        JSONObject content=at(response,"data",root?"browseStart":"browse");
        JSONObject sections=at(content,"sections");
        if(sections==null)throw new IllegalStateException("Spotify did not return Browse categories");
        JSONArray rows=sections.optJSONArray("items"),out=new JSONArray();
        if(rows!=null)for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);JSONArray cards=items(at(row,"sectionItems"),null,100);
            if(cards.length()==0)continue;
            String heading=title(at(row,"data","title"));if(heading.isEmpty())heading=root?"Browse all":"Discover";
            out.put(new JSONObject().put("title",heading).put("subtitle",plainText(at(row,"data","subtitle"))).put("items",cards));
        }
        if(out.length()==0)throw new IllegalStateException("Spotify returned no supported Browse categories or music shelves");
        return new JSONObject().put("title",root?"Browse":title(at(content,"header","title")))
            .put("subtitle",plainText(at(content,"header","subtitle")))
            .put("kind","collection").put("items",new JSONArray()).put("sections",out).put("next_offset",next(sections,offset));
    }
    static Object next(JSONObject container, int offset) {
        JSONObject paging = at(container, "pagingInfo");
        if (paging != null && paging.has("nextOffset")) {
            int next = paging.optInt("nextOffset", -1); return next > offset ? next : JSONObject.NULL;
        }
        JSONArray rows = container == null ? null : container.optJSONArray("items");
        return rows != null && rows.length() > 0 && offset + rows.length() < container.optInt("totalCount", 0) ? offset + rows.length() : JSONObject.NULL;
    }
}
