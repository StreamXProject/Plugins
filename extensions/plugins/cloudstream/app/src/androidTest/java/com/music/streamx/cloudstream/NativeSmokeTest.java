package com.music.streamx.cloudstream;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import static org.junit.Assert.*;
import org.drinkless.tdlib.JsonClient;
import org.json.JSONObject;
@RunWith(AndroidJUnit4.class)
public class NativeSmokeTest {
    @Test public void testNativeClientStartsWithoutCredentials()throws Exception {
        System.loadLibrary("tdjson");System.loadLibrary("tdjson_jni");
        JsonClient.execute(0,"{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":0}");
        long client=JsonClient.create();
        try {
            JsonClient.send(client,"{\"@type\":\"getAuthorizationState\",\"@extra\":\"smoke\"}");
            boolean found=false;long until=System.currentTimeMillis()+5000;
            while(System.currentTimeMillis()<until){String raw=JsonClient.receive(client,0.1);if(raw!=null){JSONObject value=new JSONObject(raw);if(value.optString("@type").equals("authorizationStateWaitTdlibParameters")){found=true;break;}}}
            assertTrue("TDLib JSON/JNI must load and respond on this ABI",found);
        }finally{JsonClient.destroy(client);}
    }
    @Test public void testMongoScramAndClientCanInitializeOnAndroid()throws Exception {
        com.mongodb.MongoCredential credential=com.mongodb.MongoCredential.createScramSha256Credential("fixture", "admin", "fixture".toCharArray());
        Class<?> cls=Class.forName("com.mongodb.internal.connection.ScramShaAuthenticator");
        java.lang.reflect.Constructor<?> ctor=cls.getDeclaredConstructor(com.mongodb.internal.connection.MongoCredentialWithCache.class,
            com.mongodb.connection.ClusterConnectionMode.class,com.mongodb.ServerApi.class);ctor.setAccessible(true);
        Object auth=ctor.newInstance(new com.mongodb.internal.connection.MongoCredentialWithCache(credential),com.mongodb.connection.ClusterConnectionMode.SINGLE,null);
        java.lang.reflect.Method method=cls.getDeclaredMethod("createSaslClient",com.mongodb.ServerAddress.class);method.setAccessible(true);
        javax.security.sasl.SaslClient sasl=(javax.security.sasl.SaslClient)method.invoke(auth,new com.mongodb.ServerAddress("localhost",27017));
        String first=new String(sasl.evaluateChallenge(new byte[0]),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(first.startsWith("n,,n=fixture,r="));
        String nonce=first.substring(first.indexOf(",r=")+3);
        byte[] finalMessage=sasl.evaluateChallenge(("r="+nonce+"server,s=c2FsdA==,i=4096").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(new String(finalMessage,java.nio.charset.StandardCharsets.UTF_8).contains(",p="));
        sasl.dispose();
        try(com.mongodb.client.MongoClient client=com.mongodb.client.MongoClients.create(com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(new com.mongodb.ConnectionString("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=1&connectTimeoutMS=1"))
            .applicationName("CloudStream test").build())) {assertNotNull(client.getDatabase("fixture"));}
    }

    @Test public void testAudioDescriptorSeeksWithoutReadingTheWholeFile()throws Exception {
        android.os.HandlerThread worker=new android.os.HandlerThread("range-test");worker.start();
        java.util.List<Long> offsets=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.CountDownLatch released=new java.util.concurrent.CountDownLatch(1);
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(android.os.ParcelFileDescriptor descriptor=SeekableAudio.open(context,8L*1024*1024,new android.os.Handler(worker.getLooper()),
            (offset,size,data)->{offsets.add(offset);for(int i=0;i<size;i++)data[i]=(byte)((offset+i)%251);return size;},released::countDown);
            java.io.FileInputStream input=new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            input.getChannel().position(4L*1024*1024);
            byte[] data=new byte[128];assertEquals(128,input.read(data));
            for(int i=0;i<data.length;i++)assertEquals((byte)((4L*1024*1024+i)%251),data[i]);
            assertTrue(offsets.stream().allMatch(offset->offset>=4L*1024*1024));
        }finally{assertTrue(released.await(5,java.util.concurrent.TimeUnit.SECONDS));worker.quitSafely();}
    }

}
