package com.music.streamx.cloudstream;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Reads existing StreamX/StreamXBot account metadata without modifying its collections. */
final class AccountLibrary {
    static long userId(String profile)throws IOException {
        if(!profile.startsWith("user:"))throw new IOException("Sign in to your existing account");
        try{return Long.parseLong(profile.substring(5));}catch(NumberFormatException failure){throw new IOException("Invalid account identifier");}
    }
    static Bson owner(long id){return Filters.or(Filters.eq("user_id",id),Filters.eq("user_id",Long.toString(id)));}
    private static Bson playlistId(String id){return ObjectId.isValid(id)?Filters.or(Filters.eq("_id",id),Filters.eq("_id",new ObjectId(id))):Filters.eq("_id",id);}
    static String[] names(String kind)throws IOException {
        switch(kind){
            case "favourites":return new String[]{"userFavourites","user_favourites"};
            case "playlists":return new String[]{"userPlaylists","user_playlists","playlists"};
            case "tracks":return new String[]{"playlistTracks","playlist_tracks"};
            default:throw new IOException("Unsupported account library resource");
        }
    }
    static JSONObject favourite(Document doc)throws Exception {
        String id=String.valueOf(doc.get("track_id"));
        return new JSONObject().put("key","favourite:"+id).put("kind","favourite").put("track_id",id).put("updated",timestamp(doc));
    }
    static JSONObject playlist(Document doc)throws Exception {
        Object raw=doc.get("_id");String id=raw instanceof ObjectId?((ObjectId)raw).toHexString():String.valueOf(raw);
        JSONObject out=new JSONObject().put("key","playlist:"+id).put("kind","playlist").put("playlist_id",id)
            .put("name",doc.getString("name")==null?doc.getString("title"):doc.getString("name")).put("updated",timestamp(doc));
        for(String field:new String[]{"cover_url","normal_thumbnail","cover_id"})if(doc.get(field) instanceof String)out.put(field,doc.getString(field));
        return out;
    }
    private static long timestamp(Document doc){
        Object value=doc.get("updated_at");if(value==null)value=doc.get("created_at");
        if(value instanceof Date)return ((Date)value).getTime();
        if(value instanceof Number){double n=((Number)value).doubleValue();return (long)(n<100000000000L?n*1000:n);}
        return 0;
    }
    static JSONObject page(MongoDatabase db,String profile,JSONObject query)throws Exception {
        long user=userId(profile);String kind=query.getString("kind");
        int page=Math.max(1,Math.min(100000,query.optInt("page",1)));
        int alias=query.optInt("alias",0);String[] aliases=names(kind);
        if(alias<0||alias>=aliases.length)throw new IOException("Invalid library collection");
        Bson filter=owner(user);
        if(kind.equals("tracks")) {
            String id=query.getString("playlist_id");boolean owns=false;
            for(String name:names("playlists"))if(db.getCollection(name).find(Filters.and(owner(user),playlistId(id))).projection(Projections.include("_id")).limit(1).first()!=null){owns=true;break;}
            if(!owns)throw new IOException("Playlist does not belong to this account");
            filter=Filters.eq("playlist_id",id);
        }
        MongoCollection<Document> collection=db.getCollection(aliases[alias]);
        Bson fields=kind.equals("favourites")?Projections.include("track_id","created_at","updated_at"):
            kind.equals("tracks")?Projections.include("track_id","position"):Projections.include("name","title","cover_url","normal_thumbnail","cover_id","updated_at","created_at");
        JSONArray items=new JSONArray();
        try(MongoCursor<Document> rows=collection.find(filter).projection(fields).sort(kind.equals("tracks")?Sorts.ascending("position","_id"):Sorts.ascending("_id"))
                .skip((page-1)*100).limit(101).iterator()) {
            while(rows.hasNext()) {
                Document doc=rows.next();
                if(kind.equals("tracks"))items.put(String.valueOf(doc.get("track_id")));
                else if(kind.equals("favourites"))items.put(favourite(doc));
                else items.put(playlist(doc));
            }
        }
        boolean more=items.length()>100;if(more)items.remove(100);
        EngineLog.add("Loaded "+items.length()+" account "+kind);
        return new JSONObject().put("items",items).put("more",more).put("aliases",aliases.length);
    }
}
