package com.music.streamx.extensions.applemusic

/** Keep library identity and owned uploads; MusicKit resolves their authorized resource directly. */
internal object AppleMusicPlaybackId {
    /** alacPlaybackId has already unwrapped the resource and resolved library IDs. */
    fun catalogueForAlac(id: String): String {
        require(id.matches(Regex("[0-9]{1,20}"))){"ALAC is available for catalogue songs. Use AAC for library uploads and stations."}
        return id
    }
    fun resolve(id: String): String {
        val (type,rawId)=AppleMusicJson.splitId(id)
        require(type in setOf("songs","library-songs","stations")){"This Apple Music item is not a song"}
        require(if(type=="stations")rawId.matches(Regex("[A-Za-z0-9._-]{1,180}")) else if(type=="songs")rawId.matches(Regex("[0-9]{1,24}")) else rawId.matches(Regex("i\\.[A-Za-z0-9._-]{1,178}"))){"Invalid Apple Music song ID"}
        return rawId
    }
}
