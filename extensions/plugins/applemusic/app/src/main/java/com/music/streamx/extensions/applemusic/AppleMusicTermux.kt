package com.music.streamx.extensions.applemusic

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import java.util.UUID

internal object AppleMusicTermuxLog {
    @Synchronized fun append(context: Context,line: String) {
        val prefs=context.getSharedPreferences("alac_terminal",0)
        val secrets=context.getSharedPreferences("apple_music_session",0)
        var filtered=line
        for(key in listOf("user_token","alac_wrapper_password","alac_token","alac_local_token","alac_embedded_token")) {
            val value=secrets.getString(key,"").orEmpty()
            if(value.isNotBlank())filtered=filtered.replace(value,"[redacted]")
        }
        val clean=sanitize(filtered).takeLast(16000)
        prefs.edit().putString("log",(prefs.getString("log","").orEmpty()+clean+"\n").takeLast(64000)).apply()
    }
    fun sanitize(value: String)=value.replace(Regex("(https?://)[^/\\s@]+@"),"$1[redacted]@")
        .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"),"")
        .replace(Regex("(?i)(Bearer\\s+|STREAMX_ALAC_TOKEN=)[^\\s]+"),"$1[redacted]")
        .replace(Regex("eyJ[A-Za-z0-9_.-]+"),"[redacted]")
        .replace(Regex("(?i)((?:media-user-token|password|api[_-]?token)\\s*[:=]\\s*)[^\\s]+"),"$1[redacted]")
    fun newestFirst(value: String)=value.trimEnd().lineSequence().toList().asReversed().joinToString("\n")
    fun text(context: Context)=newestFirst(context.getSharedPreferences("alac_terminal",0).getString("log","").orEmpty())
    fun clear(context: Context){context.getSharedPreferences("alac_terminal",0).edit().remove("log").apply()}
}
internal class AppleMusicTermux(private val activity: Activity) {
    fun installed()=runCatching {activity.packageManager.getPackageInfo("com.termux",0)}.isSuccess
    fun permitted()=activity.checkSelfPermission("com.termux.permission.RUN_COMMAND")==PackageManager.PERMISSION_GRANTED
    fun localToken(): String {
        val prefs=activity.getSharedPreferences("apple_music_session",0)
        return prefs.getString("alac_local_token",null) ?: run {
            val raw=ByteArray(32).also {java.security.SecureRandom().nextBytes(it)}
            raw.joinToString(""){"%02x".format(it)}.also {prefs.edit().putString("alac_local_token",it).apply()}
        }
    }
    fun useLocal() {
        AppleMusicAlac(activity).selectLocal(localToken())
    }
    fun run(task: AppleMusicTermuxScripts.Task,musicUrl: String="") {
        check(installed()){"Install Termux from its official download page first"}
        check(permitted()){"Allow this extension to run commands in Termux first"}
        val id=UUID.randomUUID().toString()
        val prefs=activity.getSharedPreferences("alac_terminal",0)
        val pending=prefs.all.filterKeys {it.startsWith("task.")}.entries.sortedBy {runCatching {JSONObject(it.value.toString()).optLong("started")}.getOrDefault(0)}
        val expired=pending.filterIndexed {index,entry->index<pending.size-31 || runCatching {System.currentTimeMillis()-JSONObject(entry.value.toString()).optLong("started")>86400000}.getOrDefault(true)}
        prefs.edit().also {editor->expired.forEach {editor.remove(it.key)}}.apply()
        prefs.edit().putString("task.$id",JSONObject().put("title",task.title).put("capture",task.capture).put("started",System.currentTimeMillis()).toString()).apply()
        val transfer=if(task in setOf(AppleMusicTermuxScripts.Task.INSTALL,AppleMusicTermuxScripts.Task.IMPORT))AppleMusicTermuxInstaller.open(activity,id,task==AppleMusicTermuxScripts.Task.IMPORT) else "http://127.0.0.1:0/unused" to "http://127.0.0.1:0/unused"
        val binary=transfer.first;val progress=transfer.second
        val callback=Intent(activity,AppleMusicTermuxResult::class.java).setData(Uri.parse("streamx-alac://task/$id"))
        val result=PendingIntent.getBroadcast(activity,id.hashCode(),callback,PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        val script=AppleMusicTermuxScripts.script(task,binary,progress,localToken(),musicUrl)
        val intent=Intent("com.termux.RUN_COMMAND").setClassName("com.termux","com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH",AppleMusicTermuxScripts.PREFIX+"/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS",arrayOf("-c",script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR",AppleMusicTermuxScripts.HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND",!task.interactive)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION","0")
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL",task.title)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",result)
        AppleMusicTermuxLog.append(activity,"› ${task.title}\nSent to Termux. Live output appears in its terminal; progress and the result appear here.")
        try {activity.startService(intent)
            if(task.interactive)activity.packageManager.getLaunchIntentForPackage("com.termux")?.let {activity.startActivity(it)}
        } catch(failure: Exception){
            result.cancel();prefs.edit().remove("task.$id").apply()
            AppleMusicTermuxInstaller.finish(activity,id)
            throw IllegalStateException("Termux could not start. Open Termux once and enable allow-external-apps in its properties.")
        }
    }
}
class AppleMusicTermuxResult: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val id=intent.data?.lastPathSegment ?: return
        val prefs=context.getSharedPreferences("alac_terminal",0)
        val saved=prefs.getString("task.$id",null) ?: return
        val task=runCatching {JSONObject(saved)}.getOrNull() ?: return
        val result=intent.getBundleExtra("result") ?: return
        val exit=result.getInt("exitCode",-1)
        val internal=result.getInt("err",-1)
        AppleMusicTermuxLog.append(context,"${task.optString("title")}: "+if(exit==0 && internal==-1)"completed" else "failed (exit $exit)")
        if(task.optBoolean("capture")) {
            AppleMusicTermuxLog.append(context,result.getString("stdout","")+"\n"+result.getString("stderr","")+"\n"+result.getString("errmsg",""))
        } else AppleMusicTermuxLog.append(context,"Private session transcript was not saved. See the Termux terminal for details.")
        prefs.edit().remove("task.$id").apply()
        if(task.optString("title")==AppleMusicTermuxScripts.Task.IMPORT.title)java.io.File(context.cacheDir,"wrapper-import.zip").delete()
        AppleMusicTermuxInstaller.finish(context,id)
    }
}
