package com.music.streamx.cloudstream;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.mongodb.MongoWriteException;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.json.*;
import java.util.*;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Read-only catalogue access; writes are confined to the explicitly app-owned library collection. */
final class CloudDatabase {
    static JSONObject discover(MongoDatabase db) throws Exception {
        JSONArray names=new JSONArray();int checked=0;
        for(String name:db.listCollectionNames()) {
            if(++checked>100) break;
            if(name.startsWith("system.") || name.equals("streamx_app_library"))continue;
            if(db.getCollection(name).find(Filters.exists("audio.title")).projection(Projections.include("_id")).limit(1).first()!=null)names.put(name);
        }
        EngineLog.add("Discovered "+names.length()+" track libraries");
        return new JSONObject().put("collections",names);
    }
    static JSONObject browse(MongoCollection<Document> tracks,JSONObject query)throws Exception {
        JSONArray path=query.optJSONArray("path");String resource=path==null?"tracks":path.optString(0);
        int limit=Math.max(1,Math.min(100,query.optInt("limit",24))),page=Math.max(1,Math.min(10000,query.optInt("page",1)));
        Bson filter=new Document();
        boolean grouped=resource.equals("albums")||resource.equals("artists");
        String field=resource.equals("artists")?"artist":"album";
        if(grouped && path.length()>2) {
            String idField=field.equals("album")?"album_id":"artist_id";
            filter=Filters.or(Filters.eq("audio."+idField,path.getString(1)),Filters.eq("audio."+field,path.getString(1)));grouped=false;
        }
        if(resource.equals("tracks") && path!=null && path.length()==2 && !path.getString(1).equals("random")) {
            Document doc=tracks.find(Filters.eq("_id",path.getString(1))).projection(trackFields()).first();
            if(doc==null)throw new java.io.IOException("Track not found");return new JSONObject(doc.toJson());
        }
        if(!Set.of("tracks","search","albums","artists").contains(resource))return new JSONObject().put("items",new JSONArray()).put("total",0);
        String search=query.optString("q").trim();
        if(!search.isEmpty()) {
            Pattern pattern=Pattern.compile(Pattern.quote(search.substring(0,Math.min(200,search.length()))),Pattern.CASE_INSENSITIVE);
            filter=Filters.or(Filters.regex("audio.title",pattern),Filters.regex("audio.artist",pattern),Filters.regex("audio.album",pattern));
        }
        if(query.has("ids")) {
            JSONArray ids=new JSONArray(query.getString("ids"));List<String> keys=new ArrayList<>();
            for(int i=0;i<Math.min(100,ids.length());i++)keys.add(ids.getString(i));filter=Filters.in("_id",keys);
        }
        JSONArray items=new JSONArray();
        MongoIterable<Document> rows;
        if(grouped) {
            String key="$audio."+field, id="$audio."+field+"_id";
            rows=tracks.aggregate(Arrays.asList(new Document("$match",new Document("audio."+field,new Document("$nin",Arrays.asList("",null)))),
                new Document("$group",new Document("_id",new Document("$ifNull",Arrays.asList(id,key))).append("name",new Document("$first",key)).append("artist",new Document("$first","$audio.artist")).append("cover_url",new Document("$first","$spotify.cover_url"))),
                new Document("$sort",new Document("name",1)),new Document("$skip",(page-1)*limit),new Document("$limit",limit+1)));
        } else if(path!=null && path.optString(1).equals("random")) rows=tracks.aggregate(Arrays.asList(new Document("$sample",new Document("size",limit)),new Document("$project",trackFields())));
        else rows=tracks.find(filter).projection(trackFields()).sort(new Document("_id",1)).skip((page-1)*limit).limit(limit+1);
        try(MongoCursor<Document> cursor=rows.iterator()){while(cursor.hasNext())items.put(new JSONObject(cursor.next().toJson()));}
        boolean more=items.length()>limit;if(more)items.remove(limit);
        EngineLog.add("Loaded "+items.length()+" "+(grouped?resource:"tracks")+" from MongoDB");
        return new JSONObject().put("items",items).put("total",(page-1)*limit+items.length()+(more?1:0));
    }
    private static Document trackFields(){return new Document("audio",1).append("cover_url",1).append("cloudflare_cover_url",1).append("normal_thumbnail",1).append("thumbnail_url",1).append("thumbnails",1).append("spotify.cover_url",1);}
    static JSONObject library(MongoDatabase db,String collection,String tracks,String profile,JSONObject query)throws Exception {
        if(!collection.equals("streamx_app_library") || collection.equals(tracks))throw new java.io.IOException("Library writes require the app-owned streamx_app_library collection");
        if(profile.isBlank()||profile.length()>100)throw new java.io.IOException("Choose a CloudStream library profile");
        MongoCollection<Document> target=db.getCollection(collection);
        String scope=tracks+":"+profile;
        JSONArray changes=query.optJSONArray("changes");
        if(changes!=null) for(int i=0;i<changes.length();i++) {
            JSONObject item=changes.getJSONObject(i);
            String key=item.getString("key");if(key.length()>512)throw new java.io.IOException("Invalid library item");
            String id=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest((scope+":"+key).getBytes(StandardCharsets.UTF_8)));
            Document value=Document.parse(item.toString()).append("scope",scope);
            try {target.updateOne(Filters.and(Filters.eq("_id",id),Filters.lte("updated",item.getLong("updated"))),new Document("$set",value),new UpdateOptions().upsert(true));}
            catch(MongoWriteException conflict){if(conflict.getError().getCode()!=11000)throw conflict;}
        }
        JSONArray items=new JSONArray(); String next="";
        Bson selection=Filters.eq("scope",scope);
        if(!query.optString("after").isBlank())selection=Filters.and(selection,Filters.gt("_id",query.getString("after")));
        try(MongoCursor<Document> cursor=target.find(selection).sort(new Document("_id",1)).limit(101).iterator()) {
            String last="";int bytes=0;
            while(cursor.hasNext()) {
                Document item=cursor.next();String id=item.getString("_id");item.remove("_id");item.remove("scope");
                String encoded=item.toJson();
                if(items.length()>0 && (items.length()>=100 || bytes+encoded.length()>350000)){next=last;break;}
                if(encoded.length()>350000)throw new java.io.IOException("Library item is too large to sync");
                items.put(new JSONObject(encoded));bytes+=encoded.length();last=id;
            }
        }
        EngineLog.add("Cloud library synced");return new JSONObject().put("items",items).put("next",next);
    }
}
