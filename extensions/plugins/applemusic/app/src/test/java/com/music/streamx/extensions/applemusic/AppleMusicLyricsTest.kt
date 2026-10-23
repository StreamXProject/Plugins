package com.music.streamx.extensions.applemusic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleMusicLyricsTest {
    private val ttml="<tt><body><p begin=\"1s\" end=\"2s\">Test line</p></body></tt>"
    @Test fun readsExactAppleApiDocument() {
        val value=JSONObject().put("data",org.json.JSONArray().put(JSONObject().put("attributes",JSONObject().put("ttml",ttml))))
        assertEquals(ttml,AppleMusicLyrics.document(value))
    }
    @Test fun readsApplesExtendedSyllableDocument() {
        val value=JSONObject().put("data",org.json.JSONArray().put(JSONObject().put("attributes",JSONObject().put("ttmlLocalizations",ttml))))
        assertEquals(ttml,AppleMusicLyrics.document(value))
    }
    @Test fun readsAccountWrapperDocument() {
        assertEquals(ttml,AppleMusicLyrics.document(JSONObject().put("data",JSONObject().put("lyrics",ttml))))
    }
    @Test fun missingAndOversizedDocumentsAreUnavailable() {
        assertNull(AppleMusicLyrics.document(JSONObject()))
        assertNull(AppleMusicLyrics.document(JSONObject().put("data",JSONObject().put("lyrics","not XML"))))
        assertNull(AppleMusicLyrics.document(JSONObject().put("data",JSONObject().put("lyrics","<tt>"+"x".repeat(120000)+"</tt>"))))
    }
    @Test fun activityShowsNewestEventsFirst() {
        assertEquals("third\nsecond\nfirst",AppleMusicTermuxLog.newestFirst("first\nsecond\nthird\n"))
    }
}
