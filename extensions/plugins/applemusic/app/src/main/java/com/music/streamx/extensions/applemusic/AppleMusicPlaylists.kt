package com.music.streamx.extensions.applemusic

import org.json.JSONArray
import org.json.JSONObject

internal object AppleMusicPlaylists {
    fun confirmedEmpty(header: JSONObject): Boolean {
        val attributes=header.optJSONObject("attributes")
        if(attributes?.has("trackCount")==true && !attributes.isNull("trackCount")) return attributes.optInt("trackCount",-1)==0
        val tracks=header.optJSONObject("relationships")?.optJSONObject("tracks") ?: return false
        return tracks.optJSONArray("data")?.length()==0 && tracks.optString("next").isBlank()
    }
    fun create(title: String): JSONObject {
        val name=title.trim()
        require(name.length in 1..200 && name.none {it<' '}){"Enter a playlist name (1–200 characters)"}
        return JSONObject().put("attributes",JSONObject().put("name",name).put("isPublic",false))
    }
    fun playlistId(id: String): String {
        val (type,raw)=AppleMusicJson.splitId(id)
        require(type=="library-playlists"){"Choose a playlist in your Apple Music library"}
        return raw
    }
    fun removalParameters(track: String): Map<String,String> {
        val (type,id)=AppleMusicJson.splitId(track)
        require(type=="library-songs") {"Choose a saved track from the playlist"}
        return mapOf("ids[$type]" to id,"mode" to "all")
    }
    fun tracks(ids: JSONArray): JSONObject {
        require(ids.length() in 1..100){"Choose between 1 and 100 tracks"}
        val rows=JSONArray()
        for(i in 0 until ids.length()) {
            val (type,id)=AppleMusicJson.splitId(ids.getString(i))
            require(type in setOf("songs","library-songs")){"Only Apple Music songs can be added"}
            rows.put(JSONObject().put("type",type).put("id",id))
        }
        return JSONObject().put("data",rows)
    }
    fun editable(response: JSONObject): JSONArray = JSONArray().apply {
        val rows=response.optJSONArray("data") ?: return@apply
        for(i in 0 until rows.length()) {
            val item=rows.optJSONObject(i) ?: continue
            if(item.optString("type")!="library-playlists" || item.optJSONObject("attributes")?.optBoolean("canEdit")!=true)continue
            AppleMusicJson.item(item)?.let(::put)
        }
    }
}
