package com.music.streamx.extensions.applemusic

import org.json.JSONArray
import org.json.JSONObject

/** Normalizes Apple's editorial tree without importing radio/video/advertising as songs. */
internal object AppleMusicEditorial {
    fun artwork(attributes: JSONObject, category: Boolean = false): JSONObject? {
        val editorial = attributes.optJSONObject("editorialArtwork")
        return if (category) editorial?.optJSONObject("subscriptionCover") ?: attributes.optJSONObject("artwork") ?: editorial?.optJSONObject("brandLogo")
        else attributes.optJSONObject("artwork") ?: editorial?.optJSONObject("subscriptionCover")
    }
    private fun title(attr: JSONObject): String = attr.optString("shortName").ifBlank { attr.optString("name") }
        .ifBlank { attr.optJSONObject("editorialNotes")?.optString("name").orEmpty() }
    fun card(resource: JSONObject): JSONObject? {
        AppleMusicJson.item(resource)?.let { return it }
        val type = resource.optString("type"); val id = resource.optString("id")
        if (!id.matches(Regex("[A-Za-z0-9._-]{1,180}"))) return null
        val attr = resource.optJSONObject("attributes") ?: return null
        val name = title(attr).takeIf { it.isNotBlank() } ?: return null
        val route = when (type) {
            "stations" -> "station:$id"
            "apple-curators", "activities", "rooms", "groupings" -> "browse:$type:$id"
            "editorial-items" -> if (attr.optJSONObject("link")?.optString("feature") == "top-charts") "charts:songs" else return null
            else -> return null
        }
        val art = artwork(attr, true)
        val url = art?.optString("url").orEmpty().replace("{w}", "800").replace("{h}", "400").replace("{f}", "jpg").replace("{c}", "bb")
        return JSONObject().put("type", "collection").put("id", route).put("title", name)
            .put("thumbnail_url", url.takeIf { it.startsWith("https://") }.orEmpty())
            .put("background_color", art?.optString("bgColor").orEmpty())
    }
    fun cards(rows: JSONArray?): JSONArray = JSONArray().apply {
        for (i in 0 until minOf(rows?.length() ?: 0, 60)) rows?.optJSONObject(i)?.let(::card)?.let(::put)
    }
    fun searchLanding(response: JSONObject): JSONObject {
        val items = JSONArray()
        val rows = response.getJSONArray("data")
        for (i in 0 until rows.length()) {
            val contents = rows.optJSONObject(i)?.optJSONObject("relationships")?.optJSONObject("contents")?.optJSONArray("data")
            val cards = cards(contents)
            for (j in 0 until cards.length()) items.put(cards.getJSONObject(j))
        }
        return JSONObject().put("sections", JSONArray().put(AppleMusicJson.section("", "Browse all", items)))
    }
    fun sections(response: JSONObject): JSONArray = JSONArray().apply {
        fun visit(resource: JSONObject, depth: Int) {
            if (depth > 7 || length() >= 24) return
            val attr = resource.optJSONObject("attributes") ?: JSONObject()
            val rel = resource.optJSONObject("relationships") ?: return
            val direct = cards(rel.optJSONObject("contents")?.optJSONArray("data"))
            if (direct.length() > 0) {
                val room = rel.optJSONObject("room")?.optJSONArray("data")?.optJSONObject(0)
                val route = room?.let { "browse:rooms:${it.optString("id")}" }.orEmpty()
                val name = title(attr).ifBlank { if (depth <= 3) "Featured" else "New on Apple Music" }
                put(AppleMusicJson.section(route, name, direct))
            }
            val children = rel.optJSONObject("children")?.optJSONArray("data")
            // Hero strips contain one-resource editorial elements; combine them into a shelf.
            if (children != null) {
                val hero = JSONArray()
                for (i in 0 until children.length()) {
                    val child = children.optJSONObject(i) ?: continue
                    val childAttr = child.optJSONObject("attributes")
                    val values = child.optJSONObject("relationships")?.optJSONObject("contents")?.optJSONArray("data")
                    if (title(childAttr ?: JSONObject()).isBlank() && values?.length() == 1) {
                        cards(values).optJSONObject(0)?.let(hero::put)
                    } else visit(child, depth + 1)
                }
                if (hero.length() > 0) put(AppleMusicJson.section("", "Featured", hero))
            }
            val tabs = rel.optJSONObject("tabs")?.optJSONArray("data")
            for (i in 0 until (tabs?.length() ?: 0)) tabs?.optJSONObject(i)?.let { visit(it, depth + 1) }
        }
        val rows = response.getJSONArray("data")
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { visit(it, 0) }
    }
}
