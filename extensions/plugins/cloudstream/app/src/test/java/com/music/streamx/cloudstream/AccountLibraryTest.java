package com.music.streamx.cloudstream;

import org.bson.Document;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountLibraryTest {
    @Test public void supportsBothDatabaseCollectionConventions()throws Exception {
        assertArrayEquals(new String[]{"userFavourites","user_favourites"},AccountLibrary.names("favourites"));
        assertArrayEquals(new String[]{"userPlaylists","user_playlists","playlists"},AccountLibrary.names("playlists"));
        assertArrayEquals(new String[]{"playlistTracks","playlist_tracks"},AccountLibrary.names("tracks"));
    }
    @Test public void convertsExistingFavouriteToLibraryIdentityAndMilliseconds()throws Exception {
        JSONObject item=AccountLibrary.favourite(new Document("track_id","track-a").append("created_at",1234.5));
        assertEquals("favourite:track-a",item.getString("key"));assertEquals(1234500L,item.getLong("updated"));
    }
    @Test public void preservesExistingPlaylistIdentifierNameAndCover()throws Exception {
        JSONObject item=AccountLibrary.playlist(new Document("_id","playlist-a").append("name","My playlist")
            .append("cover_url","https://example.com/art.jpg").append("updated_at",1700000000000L));
        assertEquals("playlist:playlist-a",item.getString("key"));assertEquals("playlist-a",item.getString("playlist_id"));
        assertEquals("My playlist",item.getString("name"));assertEquals("https://example.com/art.jpg",item.getString("cover_url"));
        assertEquals(1700000000000L,item.getLong("updated"));
    }
    @Test public void requiresAccountProfileAndPreservesLongUserIds()throws Exception {
        assertEquals(8879601665L,AccountLibrary.userId("user:8879601665"));
        try{AccountLibrary.userId("device-profile");fail();}catch(java.io.IOException expected){}
    }
}
