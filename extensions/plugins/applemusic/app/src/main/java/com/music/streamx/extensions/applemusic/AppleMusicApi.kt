package com.music.streamx.extensions.applemusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal class AppleMusicApi(context: Context) {
    val session = AppleMusicSession(context)
    private data class RemovalPlan(val generation: Long,val expires: Long,val targets: List<String>)
    private val removalPlans=java.util.concurrent.ConcurrentHashMap<String,RemovalPlan>()
    private val addedLibraryResources=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val editableIds=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private fun request(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        credentials: AppleMusicCredentials? = null,
        method: String="GET",
        body: JSONObject?=null,
    ): JSONObject {
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains('?')) { "Invalid Apple Music API path" }
        val generation = session.generation
        val suffix = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
        val url = "https://amp-api.music.apple.com$path" + if (suffix.isBlank()) "" else "?$suffix"
        fun read(auth: AppleMusicCredentials): JSONObject {
            check(generation==session.generation){"Apple Music account changed. Retry with your current account."}
            val raw=AppleMusicHttp.read(url, mapOf(
            "Authorization" to "Bearer ${auth.bearer}", "media-user-token" to auth.userToken,
            "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/"),method=method,body=body?.toString())
            return if(raw.isBlank())JSONObject() else JSONObject(raw)
        }
        val response = try { read(credentials ?: session.credentials()) }
        catch (failure: AppleMusicHttp.AuthorizationExpired) {
            if (credentials != null || method!="GET") throw failure
            session.invalidateBearer()
            read(session.credentials())
        }
        check(generation == session.generation) { "Apple Music account changed. Refresh the feed." }
        return response
    }
    fun verify(credentials: AppleMusicCredentials? = null): String {
        val storefront = request("/v1/me/storefront", credentials = credentials).optJSONArray("data")?.optJSONObject(0)?.optString("id").orEmpty()
        check(storefront.matches(Regex("[a-z]{2}"))) { "Apple Music did not return your storefront" }
        return storefront
    }
    private fun catalogue(): String {
        check(session.connected) { "Sign in to Apple Music first" }
        return "/v1/catalog/${session.storefront}"
    }
    fun editablePlaylists(offset: Int): JSONObject {
        require(offset in 0..100000){"Invalid playlist page"}
        val response=request("/v1/me/library/playlists",mapOf("offset" to offset.toString(),"limit" to "25"))
        val rows=AppleMusicPlaylists.editable(response)
        for(i in 0 until rows.length())editableIds[AppleMusicPlaylists.playlistId(rows.getJSONObject(i).getString("id"))]=session.generation
        return JSONObject().put("items",rows)
            .put("next_offset",AppleMusicJson.nextOffset(response,offset))
    }
    fun createPlaylist(title: String): JSONObject {
        val generation=session.generation
        val response=write("/v1/me/library/playlists",AppleMusicPlaylists.create(title))
        val playlist=response.optJSONArray("data")?.optJSONObject(0) ?: error("Apple Music did not return the new playlist. Refresh your library before retrying.")
        val id=AppleMusicJson.resourceId(playlist.optString("type"),playlist.optString("id"))
        editableIds[AppleMusicPlaylists.playlistId(id)]=generation
        return JSONObject().put("id",id)
    }
    fun addToPlaylist(id: String, tracks: JSONArray): JSONObject {
        val accountGeneration=session.generation
        val rawId=AppleMusicPlaylists.playlistId(id)
        val body=AppleMusicPlaylists.tracks(tracks)
        if(editableIds[rawId]!=session.generation) {
            val generation=session.generation
            val playlist=request("/v1/me/library/playlists/$rawId").getJSONArray("data").getJSONObject(0)
            check(playlist.optJSONObject("attributes")?.optBoolean("canEdit")==true){"This Apple Music playlist is read-only"}
            editableIds[rawId]=generation
        }
        check(session.generation==accountGeneration){"Apple Music account changed. Choose the playlist again."}
        write("/v1/me/library/playlists/$rawId/tracks",body)
        return JSONObject().put("ok",true)
    }
    fun removeFromPlaylist(id: String,track: String): JSONObject {
        val rawId=AppleMusicPlaylists.playlistId(id)
        val generation=session.generation
        val playlist=request("/v1/me/library/playlists/$rawId").getJSONArray("data").getJSONObject(0)
        check(playlist.optJSONObject("attributes")?.optBoolean("canEdit")==true) {"This playlist is read-only"}
        val parameters=AppleMusicPlaylists.removalParameters(track)
        check(generation==session.generation) {"Apple Music account changed"}
        request("/v1/me/library/playlists/$rawId/tracks",parameters,method="DELETE")
        return JSONObject().put("ok",true)
    }
    private fun write(path: String, body: JSONObject): JSONObject {
        // Never automatically repeat a write after an uncertain network result.
        return try {request(path,method="POST",body=body)}
        catch(failure: java.io.IOException) {throw IllegalStateException("Could not confirm the playlist change. Refresh your Apple Music library before retrying.",failure)}
    }
    fun saved(kind: String,id: String): JSONObject {
        val (type,rawId)=AppleMusicLibraryResource.type(kind,id)
        val inLibrary=if(type.startsWith("library-") || addedLibraryResources[id]==session.generation) true
            else AppleMusicLibraryResource.saved(request("${catalogue()}/$type/$rawId",mapOf("relate" to "library"))
                .getJSONArray("data").getJSONObject(0))
        return JSONObject().put("saved",inLibrary).put("can_remove",false)
    }
    fun save(kind: String,id: String,add: Boolean): JSONObject {
        val (type,rawId)=AppleMusicLibraryResource.type(kind,id)
        require(add) {"Remove this item using Apple Music"}
        if(!type.startsWith("library-")) {
            val generation=session.generation
            request("/v1/me/library",mapOf("ids[$type]" to rawId),method="POST")
            check(generation==session.generation) {"Apple Music account changed"}
            addedLibraryResources[id]=generation
        }
        return JSONObject().put("ok",true)
    }
    fun libraryItemActions(kind: String,id: String): JSONObject {
        val generation=session.generation
        val libraryId=if(id.startsWith("library-")) AppleMusicLibraryRemovals.libraryId(kind,id)
            else {
                val (catalogType,catalogId)=AppleMusicLibraryResource.type(kind,id)
                AppleMusicLibraryRemovals.libraryId(kind,id,request("${catalogue()}/$catalogType/$catalogId",mapOf("relate" to "library")))
            }
        val (type,rawId)=AppleMusicLibraryRemovals.resource(kind,libraryId)
        val header=request("/v1/me/library/${type.removePrefix("library-")}/$rawId").getJSONArray("data").getJSONObject(0)
        val name=header.getJSONObject("attributes").getString("name")
        val targets=if(kind=="artist") {
            val albums=mutableListOf<String>()
            var offset=0
            var complete=false
            for(page in 0 until 200) {
                val response=request("/v1/me/library/artists/$rawId/albums",mapOf("limit" to "25","offset" to offset.toString()))
                albums+=AppleMusicLibraryRemovals.artistAlbums(response)
                val next=AppleMusicJson.nextOffset(response,offset)
                if(next !is Int) {complete=true;break}
                offset=next
            }
            check(complete) {"Too many artist albums to remove safely"}
            albums.distinct().also {check(it.isNotEmpty()) {"This artist has no saved albums to remove"}}
        } else listOf(libraryId)
        check(generation==session.generation) {"Apple Music account changed. Open the menu again."}
        val label=if(kind=="artist")"Remove artist from library" else "Delete from Library"
        val confirmation=when(kind) {
            "artist" -> "Remove $name's ${targets.size} saved album(s), including their songs, from your Apple Music library? Songs may also be removed from playlists."
            "album" -> "Are you sure you want to delete this album from your Library? This will delete it from all your devices. Its saved songs may also be removed from playlists."
            else -> "Are you sure you want to delete this playlist from your Library? This will delete it from all your devices."
        }
        removalPlans[id]=RemovalPlan(generation,System.currentTimeMillis()+5*60_000,targets)
        return JSONObject().put("remove_label",label).put("remove_confirmation",confirmation)
    }
    fun removeLibraryItem(kind: String,id: String): JSONObject {
        if(id.startsWith("library-")) AppleMusicLibraryRemovals.resource(kind,id) else AppleMusicLibraryResource.type(kind,id)
        val plan=removalPlans.remove(id) ?: error("Open the item menu again before removing it")
        check(plan.generation==session.generation && System.currentTimeMillis()<plan.expires) {"Your library changed. Open the item menu again."}
        var removed=0
        try {
            for(target in plan.targets) {
                check(plan.generation==session.generation) {"Apple Music account changed"}
                val (type,rawId)=AppleMusicJson.splitId(target)
                check(type in setOf("library-albums","library-playlists")) {"Invalid removal target"}
                request("/v1/me/library/${type.removePrefix("library-")}/$rawId",method="DELETE")
                removed++
            }
        } catch(error: Exception) {
            if(removed>0) throw IllegalStateException("Removed $removed of ${plan.targets.size} items. Refresh your library before trying again.",error)
            throw error
        }
        addedLibraryResources.clear()
        return JSONObject().put("ok",true)
    }
    fun alacPlaybackId(id: String): String {
        val resolved=playbackId(id)
        if(!resolved.startsWith("i."))return resolved
        val rows=request("/v1/me/library/songs/"+resolved).optJSONArray("data")
        val catalogue=rows?.optJSONObject(0)?.optJSONObject("attributes")?.optJSONObject("playParams")?.optString("catalogId").orEmpty()
        check(catalogue.matches(Regex("[0-9]{1,20}"))){"This library upload has no Apple Music ALAC recording. Select AAC account streaming to play it."}
        return catalogue
    }
    fun playbackId(id: String): String = AppleMusicPlaybackId.resolve(id)
    fun account(): JSONObject = session.account()
    fun feed(kind: String): JSONObject {
        check(session.connected) { "Sign in to Apple Music first" }
        val sections = when (kind) {
            "home" -> AppleMusicJson.recommendations(request("/v1/me/recommendations", AppleMusicRequests.homeParameters())).also {
                if (it.length() == 0) it.put(AppleMusicJson.section("library:playlists", "Your playlists",
                    AppleMusicJson.items(request("/v1/me/library/playlists", mapOf("limit" to "25")).getJSONArray("data"))))
            }
            "browse" -> AppleMusicEditorial.sections(request("/v1/editorial/${session.storefront}/groupings", mapOf(
                "name" to "music", "platform" to "web", "tabs" to "subscriber", "include[songs]" to "artists,albums",
                "include[albums]" to "artists", "extend" to "editorialArtwork", "omit[resource:artists]" to "autos",
            )))
            "search_landing" -> return AppleMusicEditorial.searchLanding(request("/v1/recommendations/${session.storefront}", mapOf(
                "name" to "search-landing", "platform" to "web", "types" to "activities,apple-curators,editorial-items", "extend" to "editorialArtwork",
            ))).put("full_search",true)
            "library" -> JSONArray().apply {
                // These are library songs, not a fabricated favourites list.
                listOf("playlists" to "Your playlists", "artists" to "Your artists", "albums" to "Your albums", "songs" to "Your songs").forEach { (type, title) ->
                    put(AppleMusicJson.section("library:$type", title,
                        AppleMusicJson.items(request("/v1/me/library/$type", (if(type=="artists")mapOf("limit" to "25","include" to "catalog")else mapOf("limit" to "25"))).getJSONArray("data"))))
                }
            }
            else -> error("Unknown Apple Music feed")
        }
        return JSONObject().put("sections", sections).apply { if(kind=="browse") put("title","New").put("layout","shelves") }
    }
    private fun charts(type: String? = null): JSONArray {
        val results = request("${catalogue()}/charts", mapOf("types" to (type ?: "songs,albums,playlists"), "limit" to "25"))
            .getJSONObject("results")
        return JSONArray().apply {
            listOfNotNull(type).ifEmpty { listOf("songs", "albums", "playlists") }.forEach { resourceType ->
                val charts = results.optJSONArray(resourceType) ?: return@forEach
                for (i in 0 until charts.length()) {
                    val chart = charts.optJSONObject(i) ?: continue
                    put(AppleMusicJson.section("charts:$resourceType", chart.optString("name").ifBlank { "Top $resourceType" },
                        AppleMusicJson.items(chart.optJSONArray("data"))))
                }
            }
        }
    }
    fun search(query: String, limit: Int): JSONObject {
        require(query.length in 1..500) { "Enter a search query" }
        val result = request("${catalogue()}/search", mapOf("term" to query, "types" to "songs", "limit" to limit.coerceIn(1, 25).toString()))
        return JSONObject().put("items", AppleMusicJson.items(result.optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")))
    }
    fun searchPage(query: String, filter: String, limit: Int): JSONObject {
        require(query.length in 1..500) { "Enter a search query" }
        val types=when(filter) {"SONGS"->"songs";"ALBUMS"->"albums";"ARTISTS"->"artists";"PLAYLISTS"->"playlists";"ALL"->"songs,albums,artists,playlists";else->return JSONObject().put("items",JSONArray())}
        val result=request("${catalogue()}/search",mapOf("term" to query,"types" to types,"limit" to limit.coerceIn(1,25).toString()))
        val items=JSONArray()
        types.split(',').forEach { type ->
            val rows=AppleMusicJson.items(result.optJSONObject("results")?.optJSONObject(type)?.optJSONArray("data"))
            for(i in 0 until rows.length())items.put(rows.getJSONObject(i))
        }
        return JSONObject().put("items",items)
    }
    fun lyrics(id: String): JSONObject {
        if(!session.connected)return JSONObject()
        val song=runCatching {AppleMusicPlaybackId.catalogueForAlac(alacPlaybackId(id))}.getOrNull() ?: return JSONObject()
        for(kind in listOf("syllable-lyrics","lyrics")) {
            val found=runCatching {AppleMusicLyrics.document(request("${catalogue()}/songs/$song/$kind",mapOf("l[lyrics]" to "en", "extend" to "ttmlLocalizations")))}.getOrNull()
            if(found!=null)return JSONObject().put("ttml",found)
        }
        return JSONObject()
    }
    fun track(id: String): JSONObject {
        require(id.matches(Regex("songs:[0-9]{1,30}"))) { "Invalid Apple Music song" }
        val item = request("${catalogue()}/songs/${id.substringAfter(':')}").getJSONArray("data").optJSONObject(0)
            ?: error("This song is unavailable in your storefront")
        return AppleMusicJson.item(item) ?: error("This song has no playable metadata")
    }
    fun detail(kind: String, id: String, offset: Int): JSONObject {
        require(offset in 0..100000) { "Invalid page offset" }
        val params = mapOf("offset" to offset.toString(), "limit" to "25")
        if (kind == "collection") {
            val response: JSONObject
            val title: String
            when {
                id.startsWith("station:") -> {
                    val key=id.substringAfter(':')
                    AppleMusicJson.resourceId("stations",key)
                    val station=request("${catalogue()}/stations/$key").getJSONArray("data").getJSONObject(0)
                    val playable=AppleMusicStations.track(station)
                    return JSONObject().put("title",playable.getString("title")).put("subtitle","Apple Music Radio")
                        .put("thumbnail_url",playable.optString("thumbnail_url")).put("items",JSONArray().put(playable)).put("next_offset",JSONObject.NULL)
                }
                id.startsWith("browse:") -> {
                    val parts = id.split(':', limit = 3)
                    require(parts.size == 3 && parts[2].matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "Invalid Apple Music category" }
                    val type = parts[1]; val key = parts[2]
                    require(type in setOf("rooms", "groupings", "apple-curators", "activities")) { "Unsupported Apple Music category" }
                    val page = if (type in setOf("rooms", "groupings")) request("/v1/editorial/${session.storefront}/$type/$key", mapOf("platform" to "web", "include[songs]" to "artists,albums", "extend" to "editorialArtwork"))
                    else request("${catalogue()}/$type/$key", mapOf("include" to "playlists"))
                    val sections = if (type in setOf("rooms", "groupings")) AppleMusicEditorial.sections(page) else JSONArray().apply {
                        val header = page.getJSONArray("data").getJSONObject(0)
                        put(AppleMusicJson.section("", header.optJSONObject("attributes")?.optString("name").orEmpty(), AppleMusicJson.items(
                            request("${catalogue()}/$type/$key/playlists", params).getJSONArray("data"))))
                    }
                    return JSONObject().put("title", page.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")?.optString("name").orEmpty())
                        .put("sections", sections).put("items", JSONArray()).put("next_offset", JSONObject.NULL)
                }
                id == "library" || id.startsWith("library:") -> {
                    val type = if (id == "library") "playlists" else id.substringAfter(':')
                    require(type in setOf("playlists", "artists", "albums", "songs")) { "Unknown library collection" }
                    response = request("/v1/me/library/$type", if(type=="artists")params+mapOf("include" to "catalog")else params); title = "Your $type"
                }
                id.startsWith("recommendation:") -> {
                    val recommendation = id.substringAfter(':')
                    require(recommendation.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "Invalid recommendation" }
                    response = request("/v1/me/recommendations/$recommendation/contents", params + mapOf(
                        "name" to "listen-now", "platform" to "web", "timezone" to AppleMusicRequests.timezone(),
                    )); title = "Made for you"
                }
                id.startsWith("charts:") -> {
                    val type = id.substringAfter(':')
                    require(type in setOf("songs", "albums", "playlists")) { "Unknown chart" }
                    val section = charts(type).optJSONObject(0)
                    return JSONObject().put("title", section?.optString("title") ?: "Charts")
                        .put("items", section?.optJSONArray("items") ?: JSONArray()).put("next_offset", JSONObject.NULL)
                }
                else -> error("Unknown Apple Music collection")
            }
            return JSONObject().put("title", title).put("items", if(id.startsWith("recommendation:"))
                AppleMusicEditorial.cards(response.getJSONArray("data")) else AppleMusicJson.items(response.getJSONArray("data")))
                .put("next_offset", AppleMusicJson.nextOffset(response, offset))
        }
        val (type, rawId) = AppleMusicJson.splitId(id)
        val expected = when (kind) { "album" -> "albums"; "playlist" -> "playlists"; "artist" -> "artists"; else -> error("Unsupported collection type") }
        require(type.removePrefix("library-") == expected) { "Apple Music collection type does not match" }
        val base = if (type.startsWith("library-")) "/v1/me/library/${type.removePrefix("library-")}/$rawId" else "${catalogue()}/$type/$rawId"
        val header = request(base,if(kind=="artist") {if(type.startsWith("library-"))mapOf("include" to "catalog")else mapOf("extend" to "editorialArtwork")}else if(kind=="playlist" && type=="library-playlists")mapOf("include" to "tracks")else emptyMap()).getJSONArray("data").optJSONObject(0) ?: error("Apple Music collection is unavailable")
        val card = AppleMusicJson.item(header) ?: error("Apple Music collection has no metadata")
        // Artist pages list albums; playlists and albums list their actual ordered tracks.
        val relation = if (kind == "artist") "albums" else "tracks"
        val response = try {request("$base/$relation", params)} catch(error: AppleMusicHttp.RequestFailed) {
            if(error.status==404 && type=="library-playlists" && offset==0 && AppleMusicPlaylists.confirmedEmpty(header))
                JSONObject().put("data",JSONArray())
            else throw error
        }
        return JSONObject().put("title", card.getString("title")).put("subtitle", card.optString("subtitle"))
            .put("thumbnail_url", card.optString("thumbnail_url")).put("items", AppleMusicJson.items(response.getJSONArray("data")))
            .put("next_offset", AppleMusicJson.nextOffset(response, offset))
    }
}
