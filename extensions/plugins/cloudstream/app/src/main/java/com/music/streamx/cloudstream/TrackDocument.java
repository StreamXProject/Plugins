package com.music.streamx.cloudstream;

import org.json.JSONObject;

/** Reads StreamGO's existing document, without depending on misleading translated titles or file IDs. */
final class TrackDocument {
    static long[] source(JSONObject document) {
        JSONObject nested=document.optJSONObject("source");
        long chat=nested==null?0:nested.optLong("chat_id");
        long message=nested==null?0:nested.optLong("message_id");
        if(chat==0 || message<=0) {chat=document.optLong("source_chat_id");message=document.optLong("source_message_id");}
        if(chat==0 || message<=0) {chat=document.optLong("cache_chat_id");message=document.optLong("cache_message_id");}
        if(chat==0 || message<=0 || message>Integer.MAX_VALUE)throw new IllegalArgumentException("Track needs valid Telegram source or cache chat/message IDs");
        return new long[]{chat,message<<20}; // Bot API message number -> TDLib message identifier.
    }
    static JSONObject audio(JSONObject document) {
        JSONObject value=document.optJSONObject("audio");
        if(value==null)throw new IllegalArgumentException("Track document has no audio metadata");
        return value;
    }
}
