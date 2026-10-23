package com.music.streamx.extensions.applemusic

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.net.*
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Temporary local transfer: Android shell's content command requires a privileged permission. */
internal object AppleMusicTermuxInstaller {
    private var socket: ServerSocket?=null
    private val active=ConcurrentHashMap<String,String>()
    private val connections=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val clients=java.util.concurrent.ThreadPoolExecutor(0,4,30,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.SynchronousQueue(),java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    @Synchronized fun open(context: Context,id: String,wrapper: Boolean): Pair<String,String> {
        val nonce=UUID.randomUUID().toString().replace("-","")
        val server=socket ?: ServerSocket(0,4,InetAddress.getByName("127.0.0.1")).also {created->
            socket=created
            Thread({while(!created.isClosed){try {val client=created.accept();try {clients.execute {serve(context.applicationContext,client)}}catch(_: Exception){client.close()}}catch(_: Exception){break}}},"ALAC installer transfer").start()
        }
        active[nonce]=id
        try {context.startForegroundService(Intent(context,AppleMusicTermuxInstallService::class.java))}
        catch(failure: Exception){active.remove(nonce);if(active.isEmpty())close(context);throw failure}
        val base="http://127.0.0.1:${server.localPort}/$nonce"
        return "$base/"+(if(wrapper)"wrapper.zip" else "bridge.gz") to "$base/progress"
    }
    fun finish(context: Context,id: String){active.entries.filter {it.value==id}.forEach {active.remove(it.key)};if(active.isEmpty())close(context)}
    @Synchronized fun close(context: Context){active.clear();runCatching {socket?.close()};socket=null;connections.forEach {runCatching {it.close()}};connections.clear();context.stopService(Intent(context,AppleMusicTermuxInstallService::class.java))}
    private fun serve(context: Context,client: Socket) {
        connections.add(client)
        client.use {
            try {
                client.soTimeout=15000
                val input=client.getInputStream().buffered()
                fun line(): String {val data=ByteArrayOutputStream();while(data.size()<4096){val value=input.read();if(value<0 || value==10)break;if(value!=13)data.write(value)};check(data.size()<4096);return data.toString("UTF-8")}
                val request=line().split(' ');check(request.size==3)
                val parts=request[1].trim('/').split('/');val id=active[parts.firstOrNull()] ?: error("Expired transfer")
                var length=0
                var count=0
                while(true){check(count++<30);val header=line();if(header.isBlank())break;if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()}
                val output=client.getOutputStream()
                if(request[0]=="GET" && parts.last() in listOf("bridge.gz","wrapper.zip")) {
                    val file=if(parts.last()=="wrapper.zip")File(context.cacheDir,"wrapper-import.zip") else File(context.cacheDir,"termux-helper-${context.packageManager.getPackageInfo(context.packageName,0).lastUpdateTime}.gz").also {file->
                        synchronized(this){if(!file.isFile){
                            val runner=AppleMusicEmbeddedRuntime(context).runner
                            check(runner.canExecute()){ "Embedded helper is unavailable" }
                            java.util.zip.GZIPOutputStream(file.outputStream()).use {output->runner.inputStream().use {it.copyTo(output)}}
                        }}
                    }
                    check(file.isFile)
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n".toByteArray())
                    file.inputStream().use {it.copyTo(output,32768)}
                } else if(request[0]=="POST" && parts.last()=="progress") {
                    check(length in 0..4096)
                    val bytes=ByteArray(length);var offset=0;while(offset<length){val n=input.read(bytes,offset,length-offset);check(n>0);offset+=n}
                    AppleMusicTermuxLog.append(context,String(bytes,Charsets.UTF_8).take(500))
                    output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else error("Unknown transfer")
                output.flush()
            } catch(_: Exception){runCatching {client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())}}
            finally {connections.remove(client)}
        }
    }
}
class AppleMusicTermuxInstallService: Service() {
    private val main=Handler(Looper.getMainLooper())
    private val expire=Runnable {AppleMusicTermuxLog.append(this,"Installer transfer expired. Run the setup task again.");AppleMusicTermuxInstaller.close(this)}
    override fun onCreate(){super.onCreate();val channel="alac-install";(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(channel,"Local audio setup",NotificationManager.IMPORTANCE_LOW))
        startForeground(4202,Notification.Builder(this,channel).setSmallIcon(R.drawable.ic_music).setContentTitle("Installing local audio tools").setContentText("Transferring files to Termux on this device").setOngoing(true).build())
        main.postDelayed(expire,20*60*1000L)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int)=START_NOT_STICKY
    override fun onDestroy(){main.removeCallbacks(expire);AppleMusicTermuxInstaller.close(this);super.onDestroy()}
    override fun onTimeout(startId: Int,fgsType: Int){AppleMusicTermuxInstaller.close(this);stopSelf()}
    override fun onBind(intent: Intent?): IBinder?=null
}
