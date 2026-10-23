package com.music.streamx.cloudstream;

import org.bson.Document;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class CloudAccountTest {
    private Document fixture(String password)throws Exception {
        byte[] salt="fixed-test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] hash=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(),salt,200000,256)).getEncoded();
        return new Document("algo","pbkdf2_sha256").append("iterations",200000)
            .append("salt",Base64.getUrlEncoder().withoutPadding().encodeToString(salt))
            .append("hash",Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
    }
    @Test public void acceptsExistingStreamXPasswordFormat()throws Exception {
        Document stored=fixture("pássword-fixture");
        assertTrue(CloudAccount.verify("pássword-fixture",stored));
        assertFalse(CloudAccount.verify("incorrect",stored));
    }
    @Test public void rejectsUnsupportedOrUnboundedHashes()throws Exception {
        Document stored=fixture("fixture");
        assertFalse(CloudAccount.verify("fixture",new Document(stored).append("algo","unknown")));
        assertFalse(CloudAccount.verify("fixture",new Document(stored).append("iterations",Integer.MAX_VALUE)));
        assertFalse(CloudAccount.verify("fixture",null));
    }
}
