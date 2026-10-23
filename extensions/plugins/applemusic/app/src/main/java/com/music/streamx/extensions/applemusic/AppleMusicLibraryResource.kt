package com.music.streamx.extensions.applemusic

import org.json.JSONObject

/** Library identity is separate from catalogue identity; a rating is not a save. */
internal object AppleMusicLibraryResource {
    fun type(kind: String,id: String): Pair<String,String> {
        val resource=AppleMusicJson.splitId(id)
        val expected=when(kind) {"album"->"albums";"playlist"->"playlists";else->error("This resource cannot be added to the library")}
        require(resource.first.removePrefix("library-")==expected) {"Apple Music resource type does not match"}
        return resource
    }
    fun saved(resource: JSONObject): Boolean =
        resource.optString("type").startsWith("library-") ||
        resource.optJSONObject("meta")?.optBoolean("inLibrary")==true ||
        (resource.optJSONObject("relationships")?.optJSONObject("library")?.optJSONArray("data")?.length() ?: 0)>0
}
