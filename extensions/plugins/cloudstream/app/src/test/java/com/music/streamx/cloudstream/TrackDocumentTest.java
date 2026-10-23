package com.music.streamx.cloudstream;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class TrackDocumentTest {
    @Test public void existingDocumentUsesOriginalMessageAndShiftsOnlyMessage()throws Exception {
        JSONObject doc=new JSONObject().put("source_chat_id",-1004300252384L).put("source_message_id",6)
            .put("telegram",new JSONObject().put("file_id","not-a-tdlib-id"));
        assertArrayEquals(new long[]{-1004300252384L,6L<<20},TrackDocument.source(doc));
    }
    @Test public void acceptsNestedSourceAndCacheFallback()throws Exception {
        JSONObject doc=new JSONObject().put("source",new JSONObject().put("chat_id",-1001234567890L).put("message_id",42));
        assertEquals(42L<<20,TrackDocument.source(doc)[1]);
        assertEquals(9L<<20,TrackDocument.source(new JSONObject().put("cache_chat_id",-1004300252384L).put("cache_message_id",9))[1]);
    }
    @Test public void botApiFileIdAloneIsNotAccepted()throws Exception {
        try {TrackDocument.source(new JSONObject().put("telegram",new JSONObject().put("file_id","bot-id")));fail();}
        catch(IllegalArgumentException expected) { }
    }
    @Test public void rejectsInvalidMessageNumber()throws Exception {
        try {TrackDocument.source(new JSONObject().put("source_chat_id",-1).put("source_message_id",Long.MAX_VALUE));fail();}
        catch(IllegalArgumentException expected) { }
    }
}
