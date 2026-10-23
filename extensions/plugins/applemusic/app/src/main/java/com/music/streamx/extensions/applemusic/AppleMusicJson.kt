package com.music.streamx.extensions.applemusic

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Apple resource types remain in IDs so library and catalogue identifiers never collide. */
internal object AppleMusicJson {
    private val supported = mapOf("songs" to "track", "library-songs" to "track", "albums" to "album",
        "library-albums" to "album", "artists" to "artist", "library-artists" to "artist",
        "playlists" to "playlist", "library-playlists" to "playlist")
    fun resourceId(type: String, id: String): String {
        require((type in supported || type=="stations") && id.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "Invalid Apple Music resource" }
        return "$type:$id"
    }
    fun splitId(value: String): Pair<String, String> {
        val type = value.substringBefore(':'); val id = value.substringAfter(':', "")
        resourceId(type, id)
        return type to id
    }
    fun item(value: JSONObject): JSONObject? {
        val type = value.optString("type")
        val kind = supported[type] ?: return null
        val rawId = value.optString("id")
        val id = runCatching { resourceId(type, rawId) }.getOrNull() ?: return null
        val attr = value.optJSONObject("attributes") ?: return null
        val title = attr.optString("name").takeIf { it.isNotBlank() } ?: return null
        val artist = attr.optString("artistName").ifBlank { attr.optString("curatorName") }
        val catalogAttributes=value.optJSONObject("relationships")?.optJSONObject("catalog")?.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")
        val artwork = (attr.optJSONObject("artwork")?.optString("url")?.takeIf {it.isNotBlank()}
            ?: attr.optJSONObject("editorialArtwork")?.let {editorial ->
                listOf("subscriptionHero","storeFlowcase","bannerUber","superHeroWide").firstNotNullOfOrNull {editorial.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank)}
            } ?: catalogAttributes?.optJSONObject("artwork")?.optString("url")).orEmpty()
            .replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg").replace("{c}", "bb")
            .takeIf { runCatching { URI(it).let { u -> u.scheme == "https" && !u.host.isNullOrEmpty() } }.getOrDefault(false) }.orEmpty()
        return JSONObject().put("type", kind).put("id", id).put("title", title)
            .put("artist", artist).put("subtitle", artist).put("thumbnail_url", artwork)
            .put("album", attr.optString("albumName"))
            .put("duration_sec", (attr.optLong("durationInMillis", 0).coerceAtLeast(0) / 1000).coerceAtMost(86400))
            .put("explicit", attr.optString("contentRating") == "explicit").apply {
                listOf("albums" to "album_id", "artists" to "artist_id").forEach { (relation, field) ->
                    value.optJSONObject("relationships")?.optJSONObject(relation)?.optJSONArray("data")?.optJSONObject(0)?.let {
                        runCatching { resourceId(it.optString("type"), it.optString("id")) }.getOrNull()?.let { put(field, it) }
                    }
                }
            }
    }
    fun items(rows: JSONArray?): JSONArray = JSONArray().apply {
        for (i in 0 until minOf(rows?.length() ?: 0, 100)) rows?.optJSONObject(i)?.let(::item)?.let(::put)
    }
    fun section(id: String, title: String, rows: JSONArray): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("items", rows)
    fun recommendations(response: JSONObject): JSONArray = JSONArray().apply {
        fun append(resource: JSONObject, depth: Int) {
            if (depth > 3 || length() >= 24) return
            val attr = resource.optJSONObject("attributes")
            val titleValue = attr?.opt("title")
            val title = when (titleValue) {
                is JSONObject -> titleValue.optString("stringForDisplay")
                is String -> titleValue
                else -> attr?.optString("name").orEmpty()
            }.ifBlank { "Made for you" }
            val contents = resource.optJSONObject("relationships")?.optJSONObject("contents")?.optJSONArray("data") ?: return
            val mapped = JSONArray().apply {
                for(i in 0 until minOf(contents.length(),100))contents.optJSONObject(i)?.let(AppleMusicEditorial::card)?.let(::put)
            }
            // Recommendation arrows page through the contents of this recommendation.
            if (mapped.length() > 0) put(section("recommendation:${resource.optString("id")}", title, mapped))
            for (i in 0 until contents.length()) contents.optJSONObject(i)?.takeIf { it.optString("type") == "personal-recommendation" }
                ?.let { append(it, depth + 1) }
        }
        val rows = response.optJSONArray("data") ?: error("Apple Music returned no recommendations data")
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { append(it, 0) }
    }
    fun nextOffset(response: JSONObject, offset: Int): Any {
        val next = response.optString("next").takeIf { it.isNotBlank() } ?: return JSONObject.NULL
        // Never fetch an arbitrary URL returned by the service with our credentials.
        val parsed = runCatching { URI(next) }.getOrNull() ?: return JSONObject.NULL
        if (parsed.isAbsolute && !AppleMusicCredentials.isApiUrl(next)) return JSONObject.NULL
        if (!parsed.path.startsWith("/v1/")) return JSONObject.NULL
        val number = parsed.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "offset" }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8").toIntOrNull() }
        return number?.takeIf { it > offset } ?: JSONObject.NULL
    }
}
