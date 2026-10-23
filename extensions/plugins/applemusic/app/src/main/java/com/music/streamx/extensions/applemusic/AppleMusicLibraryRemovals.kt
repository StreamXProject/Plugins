package com.music.streamx.extensions.applemusic

import org.json.JSONObject

/** Only concrete library IDs can ever become deletion targets. */
internal object AppleMusicLibraryRemovals {
    fun resource(kind: String,id: String): Pair<String,String> {
        val pair=AppleMusicJson.splitId(id)
        val expected=when(kind) {"playlist"->"library-playlists";"album"->"library-albums";"artist"->"library-artists";else->error("Unsupported library item")}
        require(pair.first==expected) {"Choose an item already in your Apple Music library"}
        return pair
    }
    fun libraryId(kind: String,id: String,response: JSONObject? = null): String {
        if(id.startsWith("library-")) {resource(kind,id);return id}
        AppleMusicLibraryResource.type(kind,id)
        val row=response?.getJSONArray("data")?.getJSONObject(0)
            ?.optJSONObject("relationships")?.optJSONObject("library")?.optJSONArray("data")?.optJSONObject(0)
            ?: error("This item is not in your Apple Music library yet. Refresh and try again.")
        val libraryId=AppleMusicJson.resourceId(row.getString("type"),row.getString("id"))
        resource(kind,libraryId)
        return libraryId
    }
    fun artistAlbums(response: JSONObject): List<String> {
        val rows=response.getJSONArray("data")
        return (0 until rows.length()).map {index ->
            val row=rows.getJSONObject(index)
            val id=AppleMusicJson.resourceId(row.getString("type"),row.getString("id"))
            resource("album",id)
            id
        }.distinct()
    }
}
