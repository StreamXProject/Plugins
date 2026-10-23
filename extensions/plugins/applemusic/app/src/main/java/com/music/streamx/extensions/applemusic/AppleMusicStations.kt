package com.music.streamx.extensions.applemusic

import org.json.JSONObject

/** Stations are explicit radio entries, never guessed catalogue songs. */
internal object AppleMusicStations {
    fun track(station: JSONObject): JSONObject {
        require(station.optString("type")=="stations"){"This Apple Music item is not a station"}
        val id=AppleMusicJson.resourceId("stations",station.getString("id"))
        val attributes=station.getJSONObject("attributes")
        val name=attributes.getString("name")
        require(name.isNotBlank()) {"Apple Music station has no name"}
        val artwork=attributes.optJSONObject("artwork")?.optString("url").orEmpty()
            .replace("{w}","600").replace("{h}","600").replace("{f}","jpg").replace("{c}","bb")
        return JSONObject().put("type","track").put("id",id).put("title",name).put("artist","Apple Music Radio")
            .put("album","Station").put("duration_sec",0).put("thumbnail_url",artwork.takeIf {it.startsWith("https://")}.orEmpty())
    }
}
