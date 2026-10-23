package com.music.streamx.cloudstream;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
public class AndroidMongoDnsTest {
    private byte[] packet(int kind,byte[] data)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(123);out.writeShort(0x8180);out.writeShort(0);out.writeShort(1);out.writeShort(0);out.writeShort(0);
        out.writeByte(0);out.writeShort(kind);out.writeShort(1);out.writeInt(60);out.writeShort(data.length);out.write(data);return bytes.toByteArray();
    }
    @Test public void txtFragmentsAreJoined()throws Exception {
        assertEquals(Collections.singletonList("abcde"),AndroidMongoDns.parse(packet(16,new byte[]{3,'a','b','c',2,'d','e'}),123,16));
    }
    @Test public void srvRetainsPriorityWeightAndPort()throws Exception {
        byte[] value={0,0,0,5,0x69,0x55,2,'d','b',7,'e','x','a','m','p','l','e',0};
        assertEquals(Collections.singletonList("0 5 26965 db.example"),AndroidMongoDns.parse(packet(33,value),123,33));
    }
    @Test public void wrongIdAndTruncatedPacketAreRejected()throws Exception {
        try{AndroidMongoDns.parse(packet(16,new byte[]{5,'a'}),123,16);fail();}catch(IOException expected){}
        try{AndroidMongoDns.parse(packet(16,new byte[]{1,'a'}),124,16);fail();}catch(IOException expected){}
    }
}
