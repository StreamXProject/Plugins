package com.music.streamx.extensions.applemusic

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Bound only while a signed host is making catalogue calls; prevents cached-process freezing. */
class AppleMusicConnectionService : Service() {
    private val connection=Binder()
    override fun onBind(intent: Intent): IBinder=connection
}
