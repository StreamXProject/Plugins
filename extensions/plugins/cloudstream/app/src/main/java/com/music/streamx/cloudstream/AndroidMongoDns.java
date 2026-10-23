package com.music.streamx.cloudstream;

import android.content.Context;
import android.net.ConnectivityManager;
import com.mongodb.spi.dns.DnsClient;
import com.mongodb.spi.dns.DnsException;
import java.net.*;
import java.io.*;
import java.util.*;

/** Uses the active Android network's DNS servers. No JNDI or hardcoded public resolver. */
final class AndroidMongoDns implements DnsClient {
    private final Context context;
    AndroidMongoDns(Context context){this.context=context;}
    @Override public List<String> getResourceRecordData(String name,String type) {
        int kind=type.equals("SRV")?33:type.equals("TXT")?16:0;
        if(kind==0)throw new DnsException("Unsupported MongoDB DNS record type",null);
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        android.net.Network network=manager.getActiveNetwork();
        android.net.LinkProperties links=manager.getLinkProperties(network);
        if(network==null || links==null)throw new DnsException("No active network for MongoDB DNS",null);
        for(InetAddress server:links.getDnsServers()) {
            try(DatagramSocket socket=new DatagramSocket()) {
                network.bindSocket(socket);socket.connect(server,53);socket.setSoTimeout(5000);
                int id=new java.security.SecureRandom().nextInt(65536);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
                out.writeShort(id);out.writeShort(0x0100);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(0);
                for(String label:name.split("\\.")){byte[] b=label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);if(b.length>63)throw new IOException();out.writeByte(b.length);out.write(b);}
                out.writeByte(0);out.writeShort(kind);out.writeShort(1);
                byte[] query=bytes.toByteArray();socket.send(new DatagramPacket(query,query.length));
                byte[] response=new byte[65535];DatagramPacket packet=new DatagramPacket(response,response.length);socket.receive(packet);
                return parse(Arrays.copyOf(response,packet.getLength()),id,kind);
            }catch(Exception ignored) { }
        }
        throw new DnsException("MongoDB DNS lookup failed on the active network",null);
    }
    static List<String> parse(byte[] p,int id,int type)throws IOException {
        if(p.length<12 || u16(p,0)!=id || (u16(p,2)&0x8000)==0 || (u16(p,2)&0x020f)!=0)throw new IOException("Invalid DNS response");
        int pos=12;
        for(int i=0;i<u16(p,4);i++){int[] c={pos};name(p,c);pos=c[0]+4;}
        List<String> result=new ArrayList<>();
        for(int i=0;i<u16(p,6);i++) {
            int[] c={pos};name(p,c);pos=c[0];int record=u16(p,pos),cls=u16(p,pos+2),length=u16(p,pos+8);pos+=10;
            if(pos+length>p.length)throw new IOException();
            if(record==type && cls==1) {
                if(type==33 && length>=7){int[] target={pos+6};result.add(u16(p,pos)+" "+u16(p,pos+2)+" "+u16(p,pos+4)+" "+name(p,target));}
                if(type==16){StringBuilder txt=new StringBuilder();int end=pos+length;for(int n=pos;n<end;){int size=p[n++]&255;if(n+size>end)throw new IOException();txt.append(new String(p,n,size,java.nio.charset.StandardCharsets.UTF_8));n+=size;}result.add(txt.toString());}
            }
            pos+=length;
        }
        return result;
    }
    private static int u16(byte[] p,int i)throws IOException {if(i<0||i+1>=p.length)throw new IOException();return (p[i]&255)*256+(p[i+1]&255);}
    private static String name(byte[] p,int[] cursor)throws IOException {
        StringBuilder out=new StringBuilder();int i=cursor[0],end=-1,hops=0;
        while(true){if(i>=p.length || ++hops>128)throw new IOException();int size=p[i++]&255;
            if((size&192)==192){if(i>=p.length)throw new IOException();if(end<0)end=i+1;i=((size&63)<<8)|(p[i]&255);continue;}
            if(size==0)break;if(size>63||i+size>p.length)throw new IOException();
            if(out.length()>0)out.append('.');out.append(new String(p,i,size,java.nio.charset.StandardCharsets.US_ASCII));i+=size;
        }
        cursor[0]=end<0?i:end;return out.toString();
    }
}
