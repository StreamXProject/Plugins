package com.music.streamx.extensions.applemusic

import android.app.*
import android.content.*
import android.media.AudioManager
import android.os.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppleMusicPlaybackService : Service() {
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { AppleMusicRemotePlayer.pause() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Apple Music playback",NotificationManager.IMPORTANCE_LOW))
        promote()
        val filter=IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(noisy,filter)
    }
    private fun promote() {
        startForeground(73,Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Apple Music playback").setContentText("Controlled by BitChord").setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        val receiver=if(Build.VERSION.SDK_INT>=33)intent?.getParcelableExtra(READY,ResultReceiver::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra<ResultReceiver>(READY)
        receiver?.send(0,Bundle.EMPTY)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { unregisterReceiver(noisy);AppleMusicRemotePlayer.serviceDestroyed();super.onDestroy() }
    companion object {
        private const val CHANNEL="apple_music_playback"
        private const val READY="apple_music.foreground_ready"
        fun awaitForeground(context: Context) {
            check(Looper.myLooper()!=Looper.getMainLooper()) {"Playback startup must run in the background"}
            val latch=CountDownLatch(1)
            val receiver=object : ResultReceiver(null) {override fun onReceiveResult(code: Int,data: Bundle?){latch.countDown()} }
            context.startForegroundService(Intent(context,AppleMusicPlaybackService::class.java).putExtra(READY,receiver))
            check(latch.await(5,TimeUnit.SECONDS)){"Apple Music playback service did not start"}
        }
    }
}
