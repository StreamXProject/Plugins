package com.music.streamx.extensions.spotify;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpotifyBrowseTest {
    private JSONObject category() throws Exception {
        JSONObject card=new JSONObject().put("backgroundColor",new JSONObject().put("hex","#1E3264")).put("title",new JSONObject().put("text","Made For You")).put("artwork",new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("url","https://example.com/cover.jpg"))));
        return new JSONObject().put("uri","spotify:page:made-for-you").put("content",new JSONObject().put("data",new JSONObject().put("__typename","BrowseSectionContainer").put("data",new JSONObject().put("cardRepresentation",card))));
    }
    @Test public void browseCardsKeepTheirRouteTitleAndArtwork() throws Exception {
        JSONObject card=SpotifyGraph.item(category(),null);
        assertNotNull(card);assertEquals("#1E3264",card.getString("background_color"));assertEquals("collection",card.getString("type"));assertEquals("browse:spotify:page:made-for-you",card.getString("id"));assertEquals("Made For You",card.getString("title"));assertEquals("https://example.com/cover.jpg",card.getString("thumbnail_url"));
    }
    @Test public void nativeBrowseKeepsShelfHeadingsAndDoesNotRequireHome() throws Exception {
        JSONObject section=new JSONObject().put("data",new JSONObject().put("title",new JSONObject().put("text","Browse all"))).put("sectionItems",new JSONObject().put("items",new JSONArray().put(category())));
        JSONObject response=new JSONObject().put("data",new JSONObject().put("browseStart",new JSONObject().put("sections",new JSONObject().put("items",new JSONArray().put(section)))));
        JSONObject result=SpotifyGraph.browse(response,true,0);
        assertEquals("Browse all",result.getJSONArray("sections").getJSONObject(0).getString("title"));assertEquals(1,result.getJSONArray("sections").getJSONObject(0).getJSONArray("items").length());
    }
    @Test public void playlistDescriptionsAndFollowersSurviveNormalization() throws Exception {
        JSONObject playlist=new JSONObject().put("uri","spotify:playlist:1234567890123456789012").put("name","Discover Weekly")
            .put("description","Your <b>shortcut</b> to hidden gems &amp; discoveries")
            .put("followers",new JSONObject().put("totalCount",1234));
        JSONObject card=SpotifyGraph.item(playlist,null);
        assertTrue(card.getString("subtitle").startsWith("Your shortcut to hidden gems & discoveries\n"));
        assertTrue(card.getString("subtitle").endsWith(" followers"));
    }
    @Test public void unknownFollowersAreNotDisplayedAsZero() throws Exception {
        JSONObject playlist=new JSONObject().put("uri","spotify:playlist:1234567890123456789012").put("name","Editorial")
            .put("description","Curated for you");
        assertEquals("Curated for you",SpotifyGraph.item(playlist,null).getString("subtitle"));
    }
    @Test public void brokenBrowseIsReportedInsteadOfReplacingItWithHome() throws Exception {
        try{SpotifyGraph.browse(new JSONObject(),true,0);fail();}catch(IllegalStateException expected){}
    }
}
