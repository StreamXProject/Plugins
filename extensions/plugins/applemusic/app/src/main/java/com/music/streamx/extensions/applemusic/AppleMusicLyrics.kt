package com.music.streamx.extensions.applemusic

import org.json.JSONObject

/** Only an Apple TTML document crosses IPC; account credentials stay in the plugin. */
internal object AppleMusicLyrics {
    fun document(response: JSONObject): String? {
        val data=response.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")
        val text=listOf("ttmlLocalizations","ttml").firstNotNullOfOrNull {key->data?.optString(key)?.takeIf{it.isNotBlank()}}
            ?: response.optJSONObject("data")?.optString("lyrics")?.takeIf{it.isNotBlank()}
            ?: return null
        return text.takeIf {it.length<=120000 && it.contains("<tt") && it.contains("</tt>")}
    }
}
