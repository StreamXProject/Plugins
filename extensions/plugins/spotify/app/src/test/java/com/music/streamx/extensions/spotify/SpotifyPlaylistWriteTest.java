package com.music.streamx.extensions.spotify;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpotifyPlaylistWriteTest {
    private static final String ID="1234567890123456789012";
    @Test public void createTrimsNamesAndKeepsPunctuationLiteral() throws Exception {
        JSONObject op=SpotifyPlaylistWrite.create("  My \"mix\" & friends  ").getJSONArray("ops").getJSONObject(0);
        assertEquals(6,op.getInt("kind"));assertEquals("My \"mix\" & friends",op.getJSONObject("updateListAttributes").getJSONObject("newAttributes").getJSONObject("values").getString("name"));
    }
    @Test public void invalidNamesCannotSendAWritingRequest() throws Exception {
        for(String name:new String[]{"", "  ", "x".repeat(301)})try{SpotifyPlaylistWrite.create(name);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void addsInOrderAtEndAndPreservesExplicitDuplicates() throws Exception {
        JSONObject request=SpotifyPlaylistWrite.add(ID,new JSONArray().put(ID).put(ID));
        assertEquals("spotify:playlist:"+ID,request.getString("playlistUri"));assertEquals(2,request.getJSONArray("uris").length());
        assertEquals("spotify:track:"+ID,request.getJSONArray("uris").getString(0));assertEquals("BOTTOM_OF_PLAYLIST",request.getJSONObject("newPosition").getString("moveType"));
    }
    @Test public void invalidAndForeignTrackIdsAreRejected() throws Exception {
        for(String track:new String[]{"src:other::"+ID,"../playlist", "spotify:track:"+ID})try{SpotifyPlaylistWrite.add(ID,new JSONArray().put(track));fail();}catch(IllegalArgumentException expected){}
        try{SpotifyPlaylistWrite.add(ID,new JSONArray());fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void missingCreationConfirmationIsNeverInvented() throws Exception {
        assertEquals(ID,SpotifyPlaylistWrite.createdId(new JSONObject().put("uri","spotify:playlist:"+ID)));
        try{SpotifyPlaylistWrite.createdId(new JSONObject().put("id",ID));fail();}catch(IllegalStateException expected){}
    }
    @Test public void creationIsAttachedAsRootlistAdditionNotASavedEntityMutation() throws Exception {
        JSONObject delta=SpotifyPlaylistWrite.attachToLibrary(ID,1234).getJSONArray("deltas").getJSONObject(0);
        JSONObject op=delta.getJSONArray("ops").getJSONObject(0);
        assertEquals(2,op.getInt("kind"));
        assertTrue(op.getJSONObject("add").getBoolean("addFirst"));
        JSONObject item=op.getJSONObject("add").getJSONArray("items").getJSONObject(0);
        assertEquals("spotify:playlist:"+ID,item.getString("uri"));
        assertEquals(1234,item.getJSONObject("attributes").getLong("timestamp"));
        assertEquals(5,delta.getJSONObject("info").getJSONObject("source").getInt("client"));
    }
    @Test public void pickerUsesSelectedTrackAndFolderAndKeepsFolderOnlyPages() throws Exception {
        String folder="spotify:folder:"+ID;
        JSONObject variables=SpotifyPlaylistWrite.editableVariables(50,folder,ID);
        assertEquals(folder,variables.getString("folderUri"));
        assertEquals("spotify:track:"+ID,variables.getJSONArray("uris").getString(0));
        JSONObject page=new JSONObject().put("totalCount",100).put("items",new JSONArray().put(
            new JSONObject().put("item",new JSONObject().put("data",new JSONObject().put("uri",folder).put("name","Folder")))));
        JSONObject normalized=SpotifyPlaylistWrite.editablePage(page,0);
        assertEquals(0,normalized.getJSONArray("items").length());
        assertEquals(folder,normalized.getJSONArray("folders").getString(0));
        assertEquals(1,normalized.getInt("next_offset"));
        try {SpotifyPlaylistWrite.editableVariables(0,"../other",ID);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void pickerUnwrapsPlaylistDataAndDoesNotRequireArtwork() throws Exception {
        JSONObject playlist=new JSONObject().put("uri","spotify:playlist:"+ID).put("name","My playlist");
        JSONObject row=new JSONObject().put("curates",false).put("item",new JSONObject().put("data",playlist));
        JSONObject normalized=SpotifyPlaylistWrite.editablePage(new JSONObject().put("items",new JSONArray().put(row)),0);
        assertEquals(ID,normalized.getJSONArray("items").getJSONObject(0).getString("id"));
    }
    @Test public void ownedLibraryExcludesFollowedPlaylistsAndKeepsRawPagination() throws Exception {
        JSONObject owned=new JSONObject().put("uri","spotify:playlist:"+ID).put("name","Mine")
            .put("ownerV2",new JSONObject().put("data",new JSONObject().put("uri","spotify:user:me")));
        JSONObject followed=new JSONObject().put("uri","spotify:playlist:abcdefghijklmnopqrstuv").put("name","Saved")
            .put("ownerV2",new JSONObject().put("data",new JSONObject().put("uri","spotify:user:other")));
        JSONObject page=new JSONObject().put("totalCount",4).put("items",new JSONArray()
            .put(new JSONObject().put("itemV2",new JSONObject().put("data",owned)))
            .put(new JSONObject().put("itemV2",new JSONObject().put("data",followed))));
        JSONObject result=SpotifyPlaylistWrite.ownedLibraryPage(page,0,"spotify:user:me");
        assertEquals(1,result.getJSONArray("items").length());
        assertEquals("Mine",result.getJSONArray("items").getJSONObject(0).getString("title"));
        assertEquals(2,result.getInt("next_offset"));
        assertEquals(0,SpotifyPlaylistWrite.ownedLibraryPage(page,0,"spotify:user:unrelated").getJSONArray("items").length());
    }
}
