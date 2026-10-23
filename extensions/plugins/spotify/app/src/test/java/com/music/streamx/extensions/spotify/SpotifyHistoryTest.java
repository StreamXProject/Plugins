package com.music.streamx.extensions.spotify;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpotifyHistoryTest {
    private static final String ID="1234567890123456789012";
    @Test public void keepsRepeatedListensAndConvertsRestTrackMetadata() throws Exception {
        JSONObject artist=new JSONObject().put("id",ID).put("name","Artist");
        JSONObject album=new JSONObject().put("id",ID).put("name","Album").put("images",new JSONArray().put(new JSONObject().put("url","https://example.com/art.jpg")));
        JSONObject track=new JSONObject().put("id",ID).put("name","Track").put("duration_ms",244900).put("explicit",true)
            .put("artists",new JSONArray().put(artist)).put("album",album);
        JSONObject row=new JSONObject().put("track",track);
        JSONArray items=SpotifyHistory.parse(new JSONObject().put("items",new JSONArray().put(row).put(row))).getJSONArray("items");
        assertEquals(2,items.length());
        assertEquals(244,items.getJSONObject(0).getInt("duration_sec"));
        assertEquals("Artist",items.getJSONObject(0).getString("artist"));
        assertEquals("Album",items.getJSONObject(0).getString("album"));
        assertTrue(items.getJSONObject(0).getBoolean("explicit"));
    }
    @Test public void missingTracksAndEpisodesAreSkippedAndMissingHistoryIsAnError() throws Exception {
        JSONArray raw=new JSONArray().put(JSONObject.NULL).put(new JSONObject().put("track",JSONObject.NULL))
            .put(new JSONObject().put("track",new JSONObject().put("id",ID).put("type","episode")));
        assertEquals(0,SpotifyHistory.parse(new JSONObject().put("items",raw)).getJSONArray("items").length());
        try {SpotifyHistory.parse(new JSONObject().put("error","no permission"));fail();}catch(IllegalStateException expected){}
    }
    private JSONObject entity(String kind) throws Exception {
        JSONObject identity=new JSONObject().put("name","Recent "+kind).put("contributors",new JSONObject().put("items",new JSONArray().put(new JSONObject().put("name","Artist"))));
        JSONObject entity=new JSONObject().put("identityTrait",identity)
            .put("consumptionExperienceTrait",new JSONObject().put("duration",new JSONObject().put("seconds",261)))
            .put("visualIdentityTrait",new JSONObject().put("squareCoverImage",new JSONObject().put("image",new JSONObject().put("data",new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("url","https://example.com/art.jpg")))))));
        return new JSONObject().put("entity",new JSONObject().put("_uri","spotify:"+kind+":"+ID).put("data",entity));
    }
    @Test public void recentsPageRetainsTracksAndCollectionsInTheirOwnShapes() throws Exception {
        JSONObject response=new JSONObject().put("data",new JSONObject().put("lists",new JSONArray().put(new JSONObject().put("items",new JSONObject().put("items",new JSONArray().put(entity("track")).put(entity("playlist")).put(entity("album")).put(entity("episode")))))));
        JSONObject page=SpotifyHistory.recents(response);
        assertEquals(1,page.getJSONArray("items").length());
        assertEquals(261,page.getJSONArray("items").getJSONObject(0).getLong("duration_sec"));
        assertEquals("Artist",page.getJSONArray("items").getJSONObject(0).getString("artist"));
        assertEquals(2,page.getJSONArray("sections").getJSONObject(0).getJSONArray("items").length());
    }
    @Test public void recentsMissingListIsNotAnEmptySuccess() throws Exception {
        try{SpotifyHistory.recents(new JSONObject());fail();}catch(IllegalStateException expected){}
    }
}
