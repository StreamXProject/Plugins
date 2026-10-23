package com.music.streamx.extensions.applemusic

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

class AppleMusicProvider : ContentProvider() {
    private lateinit var api: AppleMusicApi
    override fun onCreate(): Boolean { api = AppleMusicApi(requireNotNull(context)); return true }
    private fun requireHost() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val ctx = requireNotNull(context)
        val allowed = setOf("com.music.streamx", "com.dev.streamx", "com.music.streamx.benchmark", "com.dev.streamx.benchmark")
        val trusted = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().any {
            it in allowed && ctx.packageManager.checkSignatures(it, ctx.packageName) == PackageManager.SIGNATURE_MATCH
        }
        if (!trusted) throw SecurityException("Untrusted StreamX host")
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireHost()
        val args = extras ?: Bundle.EMPTY
        val response = try {
            when (method) {
                "streamx.health.v1" -> JSONObject().put("ok", api.session.connected)
                    .put("message", if (api.session.connected) "Apple Music connected" else "Configure Apple Music and sign in")
                    .put("capabilities", JSONArray(listOf("home", "browse", "library", "search", "detail", "account", "playback", "playlists", "create_playlist", "add_to_playlist", "lyrics", "saved", "save", "library_item_actions", "remove_library_item", "remove_from_playlist")))
                "streamx.preferences.v1" -> preferences()
                "streamx.set_preferences.v1" -> {
                    if(args.containsKey("own_lyrics_enabled"))requireNotNull(context).getSharedPreferences("apple_music_session",0).edit().putBoolean("own_lyrics_enabled",args.getBoolean("own_lyrics_enabled")).apply()
                    if(args.containsKey("alac_endpoint"))AppleMusicAlac(requireNotNull(context)).save(args.getString("alac_endpoint").orEmpty(),args.getString("alac_token").orEmpty(),args.getInt("alac_max_rate",48000))
                    if(args.containsKey("alac_enabled"))AppleMusicAlac(requireNotNull(context)).setEnabled(args.getBoolean("alac_enabled"))
                    if(args.containsKey("quality_kbps")) {
                    val quality=AppleMusicQuality.validate(args.getInt("quality_kbps"))
                    requireNotNull(context).getSharedPreferences("apple_music_session",0).edit().putInt("quality_kbps",quality).apply()
                    }
                    preferences()
                }
                "streamx.playback_route.v1" -> JSONObject().put("backend",
                    if(AppleMusicAlac(requireNotNull(context)).enabled && !args.getString("track_id","").startsWith("stations:"))"stream" else "web")
                    .put("mime_type","application/x-mpegURL")
                "streamx.playback.v1" -> {
                    val loading=args.getString("command")=="load"
                    val ctx=requireNotNull(context)
                    val id=if(loading)api.playbackId(args.getString("track_id").orEmpty()) else null
                    if(loading)AppleMusicPlaybackService.awaitForeground(ctx)
                    try {JSONObject(AppleMusicRemotePlayer.call(ctx,args,id))}
                    catch(failure: Exception) {
                        if(loading)ctx.stopService(android.content.Intent(ctx,AppleMusicPlaybackService::class.java))
                        throw failure
                    }
                }
                "streamx.library_item_actions.v1" -> api.libraryItemActions(args.getString("kind").orEmpty(),args.getString("id").orEmpty())
                "streamx.remove_library_item.v1" -> api.removeLibraryItem(args.getString("kind").orEmpty(),args.getString("id").orEmpty())
                "streamx.saved.v1" -> api.saved(args.getString("kind").orEmpty(),args.getString("id").orEmpty())
                "streamx.save.v1" -> api.save(args.getString("kind").orEmpty(),args.getString("id").orEmpty(),args.getBoolean("saved"))
                "streamx.playlists.v1" -> api.editablePlaylists(args.getInt("offset",0))
                "streamx.create_playlist.v1" -> api.createPlaylist(args.getString("title").orEmpty())
                "streamx.remove_from_playlist.v1" -> api.removeFromPlaylist(args.getString("id").orEmpty(),args.getString("track").orEmpty())
                "streamx.add_to_playlist.v1" -> api.addToPlaylist(args.getString("id").orEmpty(),JSONArray(args.getString("tracks","[]")))
                "streamx.account.v1" -> api.account()
                "streamx.home.v1" -> api.feed("home")
                "streamx.browse.v1" -> api.feed("browse")
                "streamx.search_landing.v1" -> api.feed("search_landing")
                "streamx.library.v1" -> api.feed("library")
                "streamx.search_page.v1" -> api.searchPage(args.getString("query").orEmpty().trim(),args.getString("filter","ALL"),args.getInt("limit",25))
                "streamx.search.v1" -> api.search(args.getString("query").orEmpty().trim(), args.getInt("limit", 25))
                "streamx.detail.v1" -> api.detail(args.getString("kind").orEmpty(), args.getString("id").orEmpty(), args.getInt("offset", 0))
                "streamx.lyrics.v1" -> {
                    val enabled=requireNotNull(context).getSharedPreferences("apple_music_session",0).getBoolean("own_lyrics_enabled",true)
                    if(!enabled)JSONObject().put("enabled",false)
                    else {
                        val id=args.getString("track_id").orEmpty()
                        val document=runCatching {AppleMusicEmbeddedRuntime(requireNotNull(context)).lyrics(AppleMusicPlaybackId.catalogueForAlac(api.alacPlaybackId(id)))}.getOrNull()
                        (if(document!=null)JSONObject().put("ttml",document) else api.lyrics(id)).put("enabled",true)
                    }
                }
                "streamx.track.v1" -> api.track(args.getString("id").orEmpty())
                "streamx.sign_out.v1" -> { AppleMusicRemotePlayer.stop(); api.session.signOut(); JSONObject().put("ok", true) }
                "streamx.stream.v1" -> AppleMusicAlac(requireNotNull(context)).stream(api.alacPlaybackId(args.getString("track_id").orEmpty()))
                else -> error("Apple Music does not support this operation")
            }.also {
                if(method in setOf("streamx.home.v1","streamx.browse.v1","streamx.library.v1","streamx.search_landing.v1"))AppleMusicPageBudget.fit(it)
                check(it.toString().length <= 262144) { "Apple Music collection exceeded the response limit" } }
        } catch (failure: Exception) {
            // Never return API bodies, headers or tokens through the host bridge.
            val message = when (failure) {
                is java.io.IOException -> "Could not reach Apple Music. Check your connection and retry."
                is org.json.JSONException -> "Apple Music returned an unexpected response. Please retry."
                else -> failure.message ?: "Apple Music request failed"
            }
            JSONObject().put("error", message.take(300))
        }
        return Bundle().apply { putString("json", response.toString()) }
    }
    private fun preferences(): JSONObject = JSONObject().put("connected",api.session.connected)
        .put("name",if(api.session.connected) "Apple Music" else "")
        .put("quality_kbps",requireNotNull(context).getSharedPreferences("apple_music_session",0).getInt("quality_kbps",AppleMusicQuality.DEFAULT_KBPS))
        .put("quality_options",JSONArray(AppleMusicQuality.supported))
        .put("own_lyrics_supported",true).put("own_lyrics_enabled",requireNotNull(context).getSharedPreferences("apple_music_session",0).getBoolean("own_lyrics_enabled",true))
        .put("alac_supported",true).put("alac_enabled",AppleMusicAlac(requireNotNull(context)).enabled)
        .put("alac_configured",AppleMusicAlac(requireNotNull(context)).endpoint.isNotBlank())
        .put("lossless_available",true)
        .put("lossless_description","ALAC requires the optional fragment bridge.")
        .put("quality_description","AAC quality for account streaming when ALAC is off, and for stations. ALAC uses the separate bridge sample-rate limit.")
        .put("plan_supported",false).put("playback_mode","web")
        .put("playback_description","Account streaming uses Apple Music and requires an active subscription. Enable ALAC above for native catalogue playback through your bridge; stations continue with account streaming.")
        .put("playback_status",when(JSONObject(AppleMusicRemotePlayer.status()).optString("state")) {
            "idle"->"Ready"
            "paused"->"Paused"
            else->requireNotNull(context).getSharedPreferences("apple_music_session",0).getString("playback_status","Ready")
        })
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        requireHost();require(mode=="r"){"Read-only ALAC stream"}
        return AppleMusicAlac(requireNotNull(context)).open(uri)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
