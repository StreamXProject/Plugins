package com.music.streamx.extensions.applemusic

import org.json.JSONObject

/** Shelf previews stay small enough for concurrent Binder replies; detail pages retain all rows. */
internal object AppleMusicPageBudget {
    fun fit(page: JSONObject, limit: Int=48_000): JSONObject {
        val sections=page.optJSONArray("sections") ?: return page
        while(page.toString().length>limit) {
            val longest=(0 until sections.length()).mapNotNull {sections.optJSONObject(it)?.optJSONArray("items")}
                .filter {it.length()>1}.maxByOrNull {it.length()} ?: break
            longest.remove(longest.length()-1)
        }
        return page
    }
}
