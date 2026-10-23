package com.music.streamx.cloudstream;

import android.content.Context;
import android.os.Bundle;
import org.drinkless.tdlib.JsonClient;
import org.json.JSONObject;
import java.io.*;
import java.util.Base64;

/** One lazily created bot session; all TDLib receives are serialized with requests. */
final class TelegramFiles implements AutoCloseable {
    private final long client;
    private final Bundle credentials;
    private final Context context;
    private final String identity;
    private long sequence;
    private long lastProgress;
    private boolean ready;
    private boolean closed;
    TelegramFiles(Context context,Bundle credentials,String identity)throws Exception {
        this.context=context;this.credentials=new Bundle(credentials);this.identity=identity;
        System.loadLibrary("tdjson");System.loadLibrary("tdjson_jni");
        JsonClient.execute(0,new JSONObject().put("@type","setLogVerbosityLevel").put("new_verbosity_level",0).toString());
        client=JsonClient.create();
        try {ensureReady();}catch(Exception failure){close();throw failure;}
    }
    private void send(JSONObject request){JsonClient.send(client,request.toString());}
    private void update(JSONObject update)throws Exception {
        if(!update.optString("@type").equals("updateAuthorizationState"))return;
        JSONObject state=update.getJSONObject("authorization_state");
        switch(state.getString("@type")) {
            case "authorizationStateWaitTdlibParameters": {
                File root=new File(context.getNoBackupFilesDir(),"sessions/"+identity);if(!root.mkdirs()&&!root.isDirectory())throw new IOException("Session storage unavailable");
                send(new JSONObject().put("@type","setTdlibParameters").put("@extra","authentication")
                    .put("database_directory",new File(root,"db").getPath()).put("files_directory",new File(root,"files").getPath())
                    .put("database_encryption_key",Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(credentials.getString("api_hash","").getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .put("use_file_database",true).put("use_chat_info_database",true).put("use_message_database",false).put("use_secret_chats",false)
                    .put("api_id",credentials.getInt("api_id")).put("api_hash",credentials.getString("api_hash"))
                    .put("system_language_code","en").put("device_model","CloudStream Android").put("system_version",android.os.Build.VERSION.RELEASE).put("application_version","0.1.0"));break;
            }
            case "authorizationStateWaitPhoneNumber":
                send(new JSONObject().put("@type","checkAuthenticationBotToken").put("token",credentials.getString("bot_token")).put("@extra","authentication"));break;
            case "authorizationStateReady":ready=true;EngineLog.add("Telegram authenticated");break;
            case "authorizationStateClosed":throw new IOException("Telegram session closed");
            default: throw new IOException("Telegram needs bot authentication; check bot token and API credentials");
        }
    }
    private void ensureReady()throws Exception {
        long deadline=System.currentTimeMillis()+45000;
        while(!ready && System.currentTimeMillis()<deadline) {
            String raw=JsonClient.receive(client,0.5);if(raw==null)continue;JSONObject value=new JSONObject(raw);
            if(value.optString("@type").equals("error"))throw new IOException("Telegram authentication failed; check API ID, API hash, bot token and network access");
            update(value);
        }
        if(!ready)throw new IOException("Telegram authentication timed out");
    }
    synchronized JSONObject call(JSONObject request)throws Exception {
        if(closed)throw new IOException("CloudStream session was closed; replay the track");
        String extra=Long.toString(++sequence);send(request.put("@extra",extra));
        long deadline=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<deadline) {
            String raw=JsonClient.receive(client,0.5);if(raw==null)continue;JSONObject response=new JSONObject(raw);update(response);
            if(!extra.equals(response.optString("@extra")))continue;
            if(response.optString("@type").equals("error")) {
                EngineLog.add("Telegram "+request.optString("@type")+" failed ("+response.optInt("code")+")");
                throw new IOException("Telegram request failed ("+response.optInt("code")+"); check bot access to the source message");
            }
            return response;
        }
        throw new IOException("Telegram request timed out");
    }
    JSONObject file(JSONObject document)throws Exception {
        EngineLog.add("Locating Telegram source message…");
        long[] source=TrackDocument.source(document);
        // Public channel messages may need their chat loaded before getMessage.
        try {call(new JSONObject().put("@type","getChat").put("chat_id",source[0]));}
        catch(IOException unavailable) {
            if(source[0]>=-1000000000000L)throw unavailable;
            call(new JSONObject().put("@type","createSupergroupChat").put("supergroup_id",-source[0]-1000000000000L).put("force",false));
        }
        JSONObject message=call(new JSONObject().put("@type","getMessage").put("chat_id",source[0]).put("message_id",source[1]));
        JSONObject content=message.getJSONObject("content"),audio=content.optJSONObject("audio");
        JSONObject file=audio==null?null:audio.optJSONObject("audio");
        if(file==null){JSONObject doc=content.optJSONObject("document");file=doc==null?null:doc.optJSONObject("document");}
        if(file==null)throw new IOException("Telegram source message has no audio file");
        EngineLog.add("Telegram audio file located");
        return file;
    }
    int read(int fileId,long offset,int size,byte[] buffer)throws Exception {
        if(System.currentTimeMillis()-lastProgress>5000) {
            lastProgress=System.currentTimeMillis();EngineLog.add("Streaming audio at "+(offset/1024)+" KiB");
        }
        JSONObject file=call(new JSONObject().put("@type","downloadFile").put("file_id",fileId).put("priority",32)
            .put("offset",offset).put("limit",Math.max(size,256*1024)).put("synchronous",true));
        JSONObject local=file.getJSONObject("local");long start=local.optLong("download_offset"),prefix=local.optLong("downloaded_prefix_size");
        if(!local.optBoolean("is_downloading_completed") && (offset<start||offset>=start+prefix))throw new IOException("Telegram has not delivered the requested audio range");
        int available=local.optBoolean("is_downloading_completed")?size:(int)Math.min(size,start+prefix-offset);
        try(RandomAccessFile input=new RandomAccessFile(local.getString("path"),"r")){input.seek(offset);return input.read(buffer,0,available);}
    }
    void release(int fileId){try {call(new JSONObject().put("@type","cancelDownloadFile").put("file_id",fileId).put("only_if_pending",false));call(new JSONObject().put("@type","deleteFile").put("file_id",fileId));}catch(Exception ignored){}}
    @Override public synchronized void close(){if(closed)return;closed=true;sendQuietly();JsonClient.destroy(client);credentials.clear();}
    private void sendQuietly(){try{send(new JSONObject().put("@type","close"));}catch(Exception ignored){}}
}
