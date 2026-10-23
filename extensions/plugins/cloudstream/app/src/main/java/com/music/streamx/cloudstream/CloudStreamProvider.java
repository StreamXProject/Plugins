package com.music.streamx.cloudstream;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Signature-restricted IPC. Credentials exist only in memory; descriptors support random range reads. */
public final class CloudStreamProvider extends ContentProvider {
    private MongoClient mongo;
    private MongoCollection<Document> tracks;
    private TelegramFiles telegram;
    private String identity;
    private final LinkedHashMap<String,Entry> streams=new LinkedHashMap<>();
    private final Map<Integer,AtomicInteger> fileReaders=new HashMap<>();
    private HandlerThread reader;
    static final class Entry {
        final int fileId;final long size;final String mime;final TelegramFiles telegram;final AtomicInteger readers;
        Entry(int fileId,long size,String mime,TelegramFiles telegram,AtomicInteger readers){this.fileId=fileId;this.size=size;this.mime=mime;this.telegram=telegram;this.readers=readers;}
    }
    @Override public boolean onCreate(){java.util.logging.Logger.getLogger("org.mongodb.driver").setLevel(java.util.logging.Level.OFF);return true;}
    private void caller(){
        if(getContext().getPackageManager().checkSignatures(Binder.getCallingUid(),android.os.Process.myUid())!=PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("CloudStream requires a matching StreamX signing certificate");
    }
    private synchronized void connect(Bundle config, boolean audio)throws Exception {
        StringBuilder material=new StringBuilder();for(String key:new String[]{"bot_token","api_id","api_hash","mongo_uri","database","collection"})material.append(config.get(key)).append('\u0000');
        String next=hex(java.security.MessageDigest.getInstance("SHA-256").digest(material.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if(next.equals(identity) && mongo!=null && (!audio || telegram!=null))return;
        closeEngine();
        EngineLog.add("Connecting to MongoDB…");
        MongoClient candidate=null;TelegramFiles bot=null;
        try {
            AndroidMongoDns dns=new AndroidMongoDns(getContext());
            ConnectionString connection=new ConnectionString(config.getString("mongo_uri"),dns);
            if(Boolean.FALSE.equals(connection.getSslEnabled()))throw new IOException("MongoDB TLS must be enabled");
            MongoClientSettings settings=MongoClientSettings.builder().applyConnectionString(connection).dnsClient(dns)
                .applyToSslSettings(b->b.enabled(true).invalidHostNameAllowed(false))
                .applyToConnectionPoolSettings(b->b.maxSize(2).minSize(0).maxConnectionIdleTime(60,TimeUnit.SECONDS))
                .applyToClusterSettings(b->b.serverSelectionTimeout(15,TimeUnit.SECONDS))
                .applyToSocketSettings(b->b.connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)).build();
            candidate=MongoClients.create(settings);
            MongoCollection<Document> collection=candidate.getDatabase(config.getString("database")).getCollection(config.getString("collection", "").isBlank()?"__cloudstream_discovery__":config.getString("collection"));
            if(!config.getString("collection", "").isBlank()) collection.find().projection(Projections.include("_id")).limit(1).first();
            EngineLog.add("MongoDB connected; collection read succeeded");
            if(audio) { EngineLog.add("Connecting to Telegram…"); bot=new TelegramFiles(getContext(),config,next); }
            mongo=candidate;tracks=collection;telegram=bot;identity=next;
            EngineLog.add("CloudStream ready");
        }catch(Exception failure){if(bot!=null)bot.close();if(candidate!=null)candidate.close();throw failure;}
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();}
    private synchronized void closeEngine(){streams.clear();fileReaders.clear();if(telegram!=null)telegram.close();if(mongo!=null)mongo.close();telegram=null;mongo=null;tracks=null;identity=null;}
    @Override public Bundle call(String method,String arg,Bundle config) {
        caller();
        if (method.equals("logs") || method.equals("clearLogs")) {
            if (method.equals("clearLogs")) EngineLog.clear();
            Bundle logs = new Bundle(); logs.putString("logs", EngineLog.snapshot()); return logs;
        }
        synchronized(this) { return operate(method,arg,config); }
    }
    private Bundle operate(String method,String arg,Bundle config) {
        Bundle result=new Bundle();
        try {
            if(method.equals("close")){closeEngine();EngineLog.add("Disconnected");result.putBoolean("ok",true);return result;}
            if(!method.equals("verify") && !method.equals("prepare") && !method.equals("catalogue") && !method.equals("browse") && !method.equals("discover") && !method.equals("library") && !method.equals("bootstrap") && !method.equals("login") && !method.equals("accountLibrary"))throw new IOException("Unsupported CloudStream operation");
            if(config==null)throw new IOException("Missing CloudStream credentials");
            connect(config, method.equals("verify") || method.equals("prepare"));
            if(method.equals("bootstrap")) {
                JSONObject setup=CloudAccount.bootstrap(mongo.getDatabase(config.getString("database")));
                setup.put("collections",CloudDatabase.discover(mongo.getDatabase(config.getString("database"))).getJSONArray("collections"));
                result.putString("json",setup.toString());
            }
            if(method.equals("login")) result.putString("json",CloudAccount.login(mongo.getDatabase(config.getString("database")),new JSONObject(arg==null?"{}":arg)).toString());
            if(method.equals("discover")) result.putString("json",CloudDatabase.discover(mongo.getDatabase(config.getString("database"))).toString());
            if(method.equals("browse")) result.putString("json",CloudDatabase.browse(tracks,new JSONObject(arg==null?"{}":arg)).toString());
            if(method.equals("accountLibrary")) result.putString("json",AccountLibrary.page(mongo.getDatabase(config.getString("database")),config.getString("profile_id",""),new JSONObject(arg==null?"{}":arg)).toString());
            if(method.equals("library")) result.putString("json",CloudDatabase.library(mongo.getDatabase(config.getString("database")),config.getString("library_collection","streamx_app_library"),config.getString("collection"),config.getString("profile_id",""),new JSONObject(arg==null?"{}":arg)).toString());
            if(method.equals("catalogue")) {
                JSONObject query=new JSONObject(arg==null?"{}":arg);
                int limit=Math.max(1,Math.min(100,query.optInt("limit",24)));
                int page=Math.max(1,Math.min(10000,query.optInt("page",1)));
                String search=query.optString("q").trim();
                org.bson.conversions.Bson filter=new Document();
                if(!search.isEmpty()) {
                    java.util.regex.Pattern pattern=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(search.substring(0,Math.min(200,search.length()))),java.util.regex.Pattern.CASE_INSENSITIVE);
                    filter=Filters.or(Filters.regex("audio.title",pattern),Filters.regex("audio.artist",pattern),Filters.regex("audio.album",pattern));
                }
                EngineLog.add(search.isEmpty()?"Loading catalogue directly from MongoDB…":"Searching MongoDB catalogue…");
                org.json.JSONArray items=new org.json.JSONArray();
                try(MongoCursor<Document> cursor=tracks.find(filter).projection(Projections.include("audio","cover_url","cloudflare_cover_url","normal_thumbnail","thumbnail_url","thumbnails","spotify.cover_url"))
                        .sort(new Document("_id",1)).skip((page-1)*limit).limit(limit+1).iterator()) {
                    while(cursor.hasNext()) items.put(new JSONObject(cursor.next().toJson()));
                }
                boolean more=items.length()>limit;if(more)items.remove(limit);
                result.putString("json",new JSONObject().put("items",items).put("total",(page-1)*limit+items.length()+(more?1:0)).toString());
                EngineLog.add("Catalogue ready: "+items.length()+" tracks");
            }
            if(method.equals("prepare")) {
                if(arg==null || arg.isBlank() || arg.length()>256)throw new IOException("Invalid track identifier");
                EngineLog.add("Looking up track in MongoDB…");
                org.bson.conversions.Bson fields=Projections.include("audio","source","source_chat_id","source_message_id","cache_chat_id","cache_message_id","telegram.mime_type","telegram.file_size");
                Document doc=tracks.find(Filters.eq("_id",arg)).projection(fields).first();
                if(doc==null)doc=tracks.find(Filters.eq("telegram.file_unique_id",arg)).projection(fields).first();
                if(doc==null)throw new IOException("This track is not in the configured MongoDB collection");
                JSONObject data=new JSONObject(doc.toJson()),audio=TrackDocument.audio(data),file=telegram.file(data);
                JSONObject tg=data.optJSONObject("telegram");long size=file.optLong("size",tg==null?0:tg.optLong("file_size"));
                if(size<=0)throw new IOException("Telegram did not report the file size");
                String key=UUID.randomUUID().toString();
                while(streams.size()>=16)streams.remove(streams.keySet().iterator().next());
                int fileId=file.getInt("id");
                streams.put(key,new Entry(fileId,size,tg==null?"audio/*":tg.optString("mime_type","audio/*"),telegram,
                    fileReaders.computeIfAbsent(fileId,ignored->new AtomicInteger())));
                fileReaders.entrySet().removeIf(count->count.getValue().get()==0 && streams.values().stream().noneMatch(stream->stream.fileId==count.getKey()));
                String uri=new Uri.Builder().scheme("content").authority("com.music.streamx.cloudstream.engine").appendPath("stream").appendPath(key).build().toString();
                EngineLog.add("Track ready for streaming ("+size+" bytes)");
                result.putString("json",new JSONObject().put("uri",uri).put("audio",audio).toString());
            }
            result.putBoolean("ok",true);
        }catch(Throwable failure) {
            result.putBoolean("ok",false);
            // Driver errors can contain a credential URI. Return bounded, redacted messages only.
            String message=failure instanceof IOException || failure instanceof IllegalArgumentException?failure.getMessage():null;
            if(message==null || message.contains("mongodb") || message.contains("@") || message.length()>240)
                message="CloudStream could not connect. Check MongoDB credentials/network access and Telegram bot/channel access.";
            EngineLog.add("ERROR in "+method+": "+message);
            result.putString("error",message);
        }
        return result;
    }
    private synchronized Entry entry(Uri uri)throws FileNotFoundException {
        if(uri.getPathSegments().size()!=2 || !"stream".equals(uri.getPathSegments().get(0)))throw new FileNotFoundException();
        Entry value=streams.get(uri.getLastPathSegment());if(value==null)throw new FileNotFoundException("CloudStream stream expired; replay this track");return value;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        caller();if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        final Entry entry=entry(uri);
        EngineLog.add("Streaming audio; seekable connection opened");
        synchronized(this){if(reader==null){reader=new HandlerThread("CloudStream range reads");reader.start();}}
        synchronized(entry.readers){entry.readers.incrementAndGet();}
        try {
            return SeekableAudio.open(getContext(),entry.size,new Handler(reader.getLooper()),
                (offset,size,data)->entry.telegram.read(entry.fileId,offset,size,data),
                ()->{EngineLog.add("Audio connection closed");synchronized(entry.readers){if(entry.readers.decrementAndGet()==0)entry.telegram.release(entry.fileId);}});
        }catch(IOException failure){entry.readers.decrementAndGet();throw new FileNotFoundException("Could not create CloudStream audio descriptor");}
    }
    @Override public String getType(Uri uri){caller();try{return entry(uri).mime;}catch(Exception ignored){return "audio/*";}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){caller();MatrixCursor cursor=new MatrixCursor(new String[]{"_display_name","_size"});try{Entry e=entry(uri);cursor.addRow(new Object[]{"CloudStream audio",e.size});}catch(Exception ignored){}return cursor;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
}
