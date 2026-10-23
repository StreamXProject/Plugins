package com.music.streamx.cloudstream;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Base64;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class CloudAccount {
    static boolean verify(String password,Document stored)throws Exception {
        if(stored==null || !"pbkdf2_sha256".equals(stored.getString("algo")))return false;
        int iterations=((Number)stored.get("iterations")).intValue();
        if(iterations<1 || iterations>2000000)return false;
        byte[] salt=Base64.getUrlDecoder().decode(stored.getString("salt")),expected=Base64.getUrlDecoder().decode(stored.getString("hash"));
        if(expected.length<16 || expected.length>64 || salt.length<1 || salt.length>128)return false;
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,expected.length*8);
        try {return MessageDigest.isEqual(expected,SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
        finally {spec.clearPassword();}
    }
    static JSONObject login(MongoDatabase db,JSONObject input)throws Exception {
        String username=input.optString("username").trim().replaceFirst("^@","").toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Pattern match=java.util.regex.Pattern.compile("^"+java.util.regex.Pattern.quote(username)+"$",java.util.regex.Pattern.CASE_INSENSITIVE);
        Document user=db.getCollection("users").find(Filters.or(Filters.regex("username",match),Filters.regex("telegram.username",match))).projection(Projections.include("_id","user_id","password","first_name","username","photo_url","status")).first();
        if(user==null || !verify(input.optString("password"),user.get("password",Document.class)))throw new IOException("Invalid username or password");
        if(java.util.Set.of("locked","banned","disabled").contains(user.getString("status")))throw new IOException("This account is unavailable");
        Object id=user.get("user_id");if(id==null)id=user.get("_id");
        if(!(id instanceof Number))throw new IOException("Account has no supported user identifier");
        EngineLog.add("Existing account signed in");
        return new JSONObject().put("profile_id","user:"+id).put("name",user.getString("first_name")==null?username:user.getString("first_name")).put("username",username).put("photo",user.getString("photo_url"));
    }
    static JSONObject bootstrap(MongoDatabase db)throws Exception {
        Document doc=db.getCollection("botsettings").find(Filters.eq("_id","bot_config"))
            .projection(Projections.include("BOT_TOKEN","API_ID","API_HASH")).first();
        JSONObject result=new JSONObject();
        if(doc!=null) {
            if(doc.get("BOT_TOKEN") instanceof String)result.put("bot_token",doc.getString("BOT_TOKEN"));
            if(doc.get("API_ID")!=null)result.put("api_id",String.valueOf(doc.get("API_ID")));
            if(doc.get("API_HASH") instanceof String)result.put("api_hash",doc.getString("API_HASH"));
        }
        EngineLog.add("Database setup details checked");return result;
    }
}
