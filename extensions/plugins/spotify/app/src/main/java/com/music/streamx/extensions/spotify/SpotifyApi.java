package com.music.streamx.extensions.spotify;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;

/** Actual account/catalogue metadata. No audio URLs are invented from Spotify track IDs. */
final class SpotifyApi {
    private final Context context;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private volatile long generation = -1;
    private static final class Cached { final String json; final long expires; Cached(String json) { this.json = json; expires = System.currentTimeMillis() + 180_000; } }
    SpotifyApi(Context context) { this.context = context.getApplicationContext(); }
    JSONObject account() throws Exception {
        long current = context.getSharedPreferences("spotify_session", 0).getLong("generation", 0);
        if (!context.getSharedPreferences("spotify_session", 0).getBoolean("confirmed", false)) throw new IllegalStateException("Open Spotify account and sign in first");
        synchronized (cache) { if (current != generation) { cache.clear(); generation = current; } }
        synchronized (locks.computeIfAbsent("profileAttributes", ignored -> new Object())) {
            Cached existing = cache.get("profileAttributes");
            if (generation == current && existing != null && existing.expires > System.currentTimeMillis()) return new JSONObject(existing.json);
            SpotifyRateLimit.check(System.currentTimeMillis());
            SpotifySession.Response response = SpotifySession.profile(context);
            if (response.status == 401) { SpotifySession.invalidate(); response = SpotifySession.profile(context); }
            if (response.status == 429) throw SpotifyRateLimit.record(response.retryAfter, System.currentTimeMillis());
            if (response.status < 200 || response.status >= 300) throw new IllegalStateException("Spotify profile verification failed (HTTP " + response.status + ")");
            JSONObject profile = SpotifyProfile.parse(new JSONObject(response.body));
            if (current != context.getSharedPreferences("spotify_session", 0).getLong("generation", 0)) throw new IllegalStateException("Spotify account changed");
            context.getSharedPreferences("spotify_session", 0).edit().putBoolean("verified", true).putString("name", profile.getString("name")).putString("profile_id",profile.getString("id")).apply();
            generation = current; cache.put("profileAttributes", new Cached(profile.toString()));
            return profile;
        }
    }
    JSONObject knownAccount() throws Exception {
        android.content.SharedPreferences state=context.getSharedPreferences("spotify_session",0);
        String id=state.getString("profile_id","");
        if(state.getBoolean("confirmed",false) && state.getBoolean("verified",false) && id.startsWith("spotify:user:"))
            return new JSONObject().put("id",id).put("name",state.getString("name","")).put("thumbnail_url","");
        return account();
    }
    JSONObject plan(boolean refresh) throws Exception {
        if (refresh) cache.keySet().removeIf(key -> key.startsWith("accountAttributes"));
        return SpotifyPlan.parse(graph("accountAttributes", "4fbd57be3c6ec2157adcc5b8573ec571f61412de23bbb798d8f6a156b7d34cdf", new JSONObject()));
    }
    JSONObject playbackRoute() throws Exception {
        JSONObject subscription;
        try { subscription = plan(false); }
        catch (Exception unavailable) { subscription = new JSONObject().put("tier", "unknown"); }
        return new JSONObject().put("tier",subscription.optString("tier","unknown"))
            .put("backend",SpotifyPlan.permitsPlayback(subscription)?"spotify":"youtube");
    }
    JSONObject search(String query, int limit) throws Exception {
        JSONObject response = graph("searchTracks", "5307479c18ff24aa1bd70691fdb0e77734bede8cce3bd7d43b6ff7314f52a6b8",
            new JSONObject().put("searchTerm", query).put("offset", 0).put("limit", Math.min(limit, 10))
                .put("numberOfTopResults", 1).put("includeAudiobooks", false).put("includePreReleases", true));
        return new JSONObject().put("items", SpotifyGraph.items(SpotifyGraph.at(response, "data", "searchV2", "tracksV2"), null, 10));
    }
    private JSONObject graph(String operation, String hash, JSONObject variables) throws Exception {
        long current = context.getSharedPreferences("spotify_session", 0).getLong("generation", 0);
        synchronized (cache) { if (current != generation) { cache.clear(); generation = current; } }
        if (!context.getSharedPreferences("spotify_session", 0).getBoolean("confirmed", false)) throw new IllegalStateException("Open Spotify account and sign in first");
        String key = operation + variables.toString();
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            Cached existing = cache.get(key);
            if (existing != null && existing.expires > System.currentTimeMillis()) return new JSONObject(existing.json);
            SpotifyRateLimit.check(System.currentTimeMillis());
            SpotifySession.Response response = SpotifySession.graph(context, operation, hash, variables);
            if (response.status == 401) { SpotifySession.invalidate(); response = SpotifySession.graph(context, operation, hash, variables); }
            if (response.status == 429) throw SpotifyRateLimit.record(response.retryAfter, System.currentTimeMillis());
            if (response.status < 200 || response.status >= 300) throw new IllegalStateException("Spotify " + operation + " failed (HTTP " + response.status + ")");
            JSONObject result = new JSONObject(response.body);
            JSONArray errors = result.optJSONArray("errors");
            if (errors != null && errors.length() > 0) throw new IllegalStateException("Spotify rejected " + operation + ". Its web-player query or session may have changed.");
            if (current != context.getSharedPreferences("spotify_session", 0).getLong("generation", 0)) throw new IllegalStateException("Spotify account changed");
            cache.put(key, new Cached(result.toString())); return result;
        }
    }
    private String libraryUri(String kind, String id) {
        if (!java.util.Set.of("track", "playlist", "album", "artist").contains(kind) || id == null || !id.matches("[A-Za-z0-9]{22}"))
            throw new IllegalArgumentException("Invalid Spotify library item");
        return "spotify:" + kind + ":" + id;
    }
    JSONObject saved(String kind, String id) throws Exception {
        JSONObject response = graph("areEntitiesInLibrary", "6ec3f767111e1f88a68058560f961161679d2cd4805ff3b8cb4b25c83ccbd6e0",
            new JSONObject().put("uris", new JSONArray().put(libraryUri(kind,id))));
        JSONObject data = response.optJSONObject("data");
        JSONArray lookup = data == null ? null : data.optJSONArray("lookup");
        JSONObject item = lookup == null || lookup.length()==0 ? null : SpotifyGraph.at(lookup.optJSONObject(0), "data");
        if (item == null || !item.has("saved")) throw new IllegalStateException("Spotify did not report this item's saved state");
        return new JSONObject().put("saved", item.getBoolean("saved"));
    }
    synchronized JSONObject save(String kind, String id, boolean saved) throws Exception {
        String uri = libraryUri(kind,id);
        // Mutations must never reuse the read cache, including repeated like/unlike cycles.
        cache.clear();
        try {
            graph(saved ? "addToLibrary" : "removeFromLibrary", "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915",
                new JSONObject().put("uris", new JSONArray().put(uri)));
        } finally { cache.clear(); }
        JSONObject actual = saved(kind,id);
        if (actual.getBoolean("saved") != saved) throw new IllegalStateException("Spotify did not apply the library change");
        return actual;
    }
    JSONObject editablePlaylists(int offset, String folder, String track) throws Exception {
        // A picker opened after an edit must not reuse the three-minute feed cache.
        cache.keySet().removeIf(key -> key.startsWith("editablePlaylists") || key.startsWith("libraryV3"));
        if(track==null && folder==null)return SpotifyPlaylistWrite.ownedLibraryPage(libraryPage(offset),offset,knownAccount().getString("id"));
        JSONObject response = graph("editablePlaylists", "acb5390f2929bdcad4c6afe1c08bdbe09375f50fdb29d75244f67e9aee77ebc4",
            SpotifyPlaylistWrite.editableVariables(offset, folder, track));
        JSONObject page = SpotifyGraph.at(response, "data", "me", "editablePlaylists");
        if (page == null) throw new IllegalStateException("Spotify did not return editable playlists");
        return SpotifyPlaylistWrite.editablePage(page, offset);
    }
    JSONObject history() throws Exception {
        knownAccount();
        String identity=SpotifyLocalHistory.identity(context);
        JSONArray local=SpotifyLocalHistory.read(context);
        try {
            JSONObject remotePage=recents();
            JSONArray remote=remotePage.getJSONArray("items");
            if(!identity.equals(SpotifyLocalHistory.identity(context)))throw new IllegalStateException("Spotify account changed");
            return SpotifyLocalHistory.merge(local,remote).put("sections",remotePage.optJSONArray("sections"));
        }
        catch(Exception failure) {
            if(local.length()==0 || !identity.equals(SpotifyLocalHistory.identity(context)))throw failure;
            return SpotifyLocalHistory.merge(local,new JSONArray()).put("warning","Spotify history is unavailable; showing listens from this device.");
        }
    }
    private JSONObject recents() throws Exception {
        cache.keySet().removeIf(key->key.startsWith("recents{"));
        JSONObject response=graph("recents","698be5892a3cc95331deebeff463d05dfdd5febf5254bea30b895b5a93dfb584",
            new JSONObject().put("uris",new JSONArray().put("spotify:list:recents:page")).put("offset",0).put("limit",50));
        return SpotifyHistory.recents(response);
    }
    private JSONObject remoteHistory() throws Exception {
        knownAccount(); SpotifyRateLimit.check(System.currentTimeMillis());
        long identity = context.getSharedPreferences("spotify_session", 0).getLong("generation", 0);
        SpotifySession.Response response = SpotifySession.history(context);
        if (response.status == 401) { SpotifySession.invalidate(); response = SpotifySession.history(context); }
        if (identity != context.getSharedPreferences("spotify_session", 0).getLong("generation", 0)) throw new IllegalStateException("Spotify account changed");
        if (response.status == 429) throw SpotifyRateLimit.record(response.retryAfter, System.currentTimeMillis());
        if (response.status == 403) throw new IllegalStateException("Spotify did not allow this session to read listening history. Reconnect your Spotify account.");
        if (response.status < 200 || response.status >= 300) throw new IllegalStateException("Spotify history failed (HTTP " + response.status + ")");
        return SpotifyHistory.parse(new JSONObject(response.body));
    }
    synchronized JSONObject createPlaylist(String title) throws Exception {
        JSONObject user=account();SpotifyRateLimit.check(System.currentTimeMillis());
        long identity=context.getSharedPreferences("spotify_session",0).getLong("generation",0);
        String name=title==null?"":title.trim();
        JSONObject body=SpotifyPlaylistWrite.create(title);
        SpotifySession.Response response=SpotifySession.createPlaylist(context,body.toString());
        cache.clear();
        if(identity!=context.getSharedPreferences("spotify_session",0).getLong("generation",0))throw new IllegalStateException("Spotify account changed while creating the playlist");
        if(response.status==429)throw SpotifyRateLimit.record(response.retryAfter,System.currentTimeMillis());
        if(response.status<200 || response.status>=300)throw new IllegalStateException("Spotify playlist creation failed (HTTP "+response.status+")");
        String id=SpotifyPlaylistWrite.createdId(new JSONObject(response.body));
        // Creation and root-list insertion are separate writes. Never repeat creation if insertion fails.
        String warning="";
        try {
            SpotifySession.Response attached=SpotifySession.attachPlaylist(context,user.getString("id").substring("spotify:user:".length()),
                SpotifyPlaylistWrite.attachToLibrary(id,System.currentTimeMillis()).toString());
            if(attached.status==429)throw SpotifyRateLimit.record(attached.retryAfter,System.currentTimeMillis());
            if(attached.status<200 || attached.status>=300)throw new IllegalStateException("HTTP "+attached.status);
            JSONObject confirmation=attached.body.isBlank()?new JSONObject():new JSONObject(attached.body);
            if(confirmation.optJSONArray("errors")!=null && confirmation.getJSONArray("errors").length()>0)throw new IllegalStateException("Spotify rejected the library update");
        } catch(Exception failure) {warning="Playlist created, but Spotify did not confirm adding it to your library. Do not create it again. Playlist: https://open.spotify.com/playlist/"+id;}
        finally {cache.clear();}
        return new JSONObject().put("id",id).put("title",name).put("warning",warning);
    }
    synchronized JSONObject addToPlaylist(String playlistId,JSONArray tracks) throws Exception {
        JSONObject variables=SpotifyPlaylistWrite.add(playlistId,tracks);
        cache.clear();
        try {
            JSONObject response=graph("addToPlaylist","47c69e71df79e3c80e4af7e7a9a727d82565bb20ae20dc820d6bc6f94def482d",
                variables);
            if(response.optJSONObject("data")==null)throw new IllegalStateException("Spotify did not confirm the playlist update");
            return new JSONObject().put("ok",true);
        } finally {cache.clear();}
    }
    private JSONObject browse(String uri,int offset) throws Exception {
        boolean root=uri==null;
        JSONObject variables=new JSONObject().put("pagePagination",new JSONObject().put("offset",offset).put("limit",10))
            .put("sectionPagination",new JSONObject().put("offset",0).put("limit",root?99:10));
        if(!root)variables.put("uri",uri);
        JSONObject response=graph(root?"browseAll":"browsePage",root?"cd6fcd0ce9d1849477645646601a6d444597013355467e24066dad2c1dc9b740":"d8346883162a16a62a5b69e73e70c66a68c27b14265091cd9e1517f48334bbb3",variables);
        return SpotifyGraph.browse(response,root,offset);
    }
    private JSONObject homeVariables() throws Exception {
        return new JSONObject().put("timeZone", java.util.TimeZone.getDefault().getID()).put("sp_t", "");
    }
    JSONObject feed(String mode) throws Exception {
        JSONObject user=knownAccount();
        String identity=user.getString("id"),key=identity+":"+mode;
        android.content.SharedPreferences stored=context.getSharedPreferences("spotify_feed_cache",0);
        try {
            JSONObject result=liveFeed(mode);
            if(identity.equals(SpotifyLocalHistory.identity(context)))stored.edit().putString(key,result.toString()).putLong(key+":time",System.currentTimeMillis()).apply();
            return result;
        } catch(Exception failure) {
            if(!identity.equals(SpotifyLocalHistory.identity(context)) || System.currentTimeMillis()-stored.getLong(key+":time",0)>86400000)throw failure;
            String saved=stored.getString(key,null);if(saved==null)throw failure;
            JSONObject result=new JSONObject(saved);
            JSONArray sections=result.optJSONArray("sections");
            if(sections!=null && sections.length()>0)sections.getJSONObject(0).put("subtitle","Saved feed · Spotify is temporarily unavailable");
            return result.put("stale",true);
        }
    }
    private JSONObject liveFeed(String mode) throws Exception {
        JSONObject user = knownAccount();
        if (mode.equals("library")) return library(user);
        if(mode.equals("browse"))return browse(null,0).put("account",user);
        JSONObject response = graph("home", "3357ffed7961629ba92b4e0a41514e4d5004a14355c964c23ce442205c9e44a1",
            homeVariables().put("facet", "").put("sectionItemsLimit", 10));
        return SpotifyGraph.home(response).put("account", user);
    }
    private JSONObject libraryPage(int offset) throws Exception {
        JSONObject response = graph("libraryV3", "866c12e82c0edea47a0b9e8f91f35602b6e6ce75740a6da2c790c0003a70fcd1",
            new JSONObject().put("filters", new JSONArray()).put("order", "Recents").put("textFilter", "")
                .put("features", new JSONArray(new String[]{"LIKED_SONGS", "YOUR_EPISODES"})).put("limit", 25).put("offset", offset)
                .put("flatten", true).put("expandedFolders", new JSONArray()).put("folderUri", JSONObject.NULL).put("includeFoldersWhenFlattening", true));
        JSONObject page = SpotifyGraph.at(response, "data", "me", "libraryV3");
        if (page == null) throw new IllegalStateException("Spotify did not return your library"); return page;
    }
    private JSONObject likedPage(int offset) throws Exception {
        JSONObject response = graph("fetchLibraryTracks", "1cb5df9343e3e11ecca539ee85621136f8c1226768a9b7641012c4e6a2339872", new JSONObject().put("limit", 50).put("offset", offset));
        JSONObject page = SpotifyGraph.at(response, "data", "me", "library", "tracks");
        if (page == null) throw new IllegalStateException("Spotify did not return your liked songs"); return page;
    }
    private JSONObject library(JSONObject user) throws Exception {
        JSONObject page = libraryPage(0);
        JSONArray all = SpotifyGraph.items(page, null, 25), sections = new JSONArray(), warnings = new JSONArray();
        for (String type : new String[]{"playlist", "album", "artist"}) {
            JSONArray cards = new JSONArray();
            for (int i = 0; i < all.length(); i++) if (type.equals(all.getJSONObject(i).optString("type"))) cards.put(all.getJSONObject(i));
            if (cards.length() > 0) sections.put(new JSONObject().put("id", "library").put("title", type.equals("playlist") ? "Playlists" : type.equals("album") ? "Albums" : "Artists").put("items", cards));
        }
        try {
            JSONArray liked = SpotifyGraph.items(likedPage(0), null, 50);
            if (liked.length() > 0) sections.put(new JSONObject().put("id", "liked").put("title", "Liked songs").put("items", liked));
        } catch (Exception failure) { warnings.put("Liked songs: " + failure.getMessage()); }
        return new JSONObject().put("account", user).put("sections", sections).put("warnings", warnings);
    }
    JSONObject detail(String kind, String id, int offset) throws Exception {
        if (kind.equals("collection")) {
            JSONObject page; String title;
            if(id.startsWith("browse:") && id.length()<=1024)return browse(id.substring(7),offset);
            if (id.startsWith("home:") && id.length() <= 1024) {
                JSONObject response = graph("homeSection", "b3ef823f52e8e4c30e693ef24431b89760be10b429f2563ceaf169846dc5c4ab",
                    homeVariables().put("uri", id.substring(5)).put("sectionItemsOffset", offset).put("sectionItemsLimit", 20));
                JSONObject container = SpotifyGraph.at(response, "data", "homeSections");
                JSONArray sections = container == null ? null : container.optJSONArray("sections");
                JSONObject section = sections == null ? null : sections.optJSONObject(0);
                page = SpotifyGraph.at(section, "sectionItems"); title = SpotifyGraph.title(SpotifyGraph.at(section, "data", "title"));
            } else if (id.equals("library")) { page = libraryPage(offset); title = "Your library"; }
            else if (id.equals("liked")) { page = likedPage(offset); title = "Liked songs"; }
            else throw new IllegalArgumentException("Unknown Spotify collection");
            if (page == null) throw new IllegalStateException("Spotify did not return this section");
            return new JSONObject().put("title", title).put("kind", "collection").put("items", SpotifyGraph.items(page, null, 50)).put("next_offset", SpotifyGraph.next(page, offset));
        }
        if (!id.matches("[A-Za-z0-9]{22}")) throw new IllegalArgumentException("Invalid Spotify item ID");
        JSONObject info, page, variables = new JSONObject().put("uri", "spotify:" + kind + ":" + id).put("offset", offset);
        switch (kind) {
            case "playlist":
                JSONObject metadata = graph("fetchPlaylistWithGatedEntityRelations", "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d", new JSONObject().put("uri", "spotify:playlist:" + id));
                JSONObject contents = graph("fetchPlaylistContentsWithGatedEntityRelations", "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d", variables.put("limit", 25));
                return SpotifyPlaylist.parse(metadata, contents, id, offset);
            case "album":
                info = SpotifyGraph.at(graph("getAlbum", "8f4cd5650f9d80349dbe68684057476d8bf27a5c51687b2b1686099ab5631589", variables.put("locale", "").put("limit", 50)), "data", "albumUnion");
                page = SpotifyGraph.at(info, "tracksV2"); break;
            case "artist":
                info = SpotifyGraph.at(graph("queryArtistOverview", "4bc52527bb77a5f8bbb9afe491e9aa725698d29ab73bff58d49169ee29800167", new JSONObject().put("uri", "spotify:artist:" + id).put("locale", "")), "data", "artistUnion");
                page = SpotifyGraph.at(info, "discography", "topTracks"); break;
            default: throw new IllegalArgumentException("Unknown Spotify page type");
        }
        JSONObject card = SpotifyGraph.item(info, null);
        if (card == null) throw new IllegalStateException("Spotify did not return this music collection");
        return new JSONObject().put("title", card.optString("title")).put("subtitle", card.optString("subtitle"))
            .put("thumbnail_url", card.optString("thumbnail_url")).put("kind", kind)
            .put("items", SpotifyGraph.items(page, kind.equals("album") ? info : null, 50))
            .put("next_offset", kind.equals("artist") ? JSONObject.NULL : SpotifyGraph.next(page, offset));
    }
}
