package com.music.streamx.cloudstream;

import android.content.Context;
import android.os.*;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.IOException;

/** Android supplies random-access reads to Media3 without a local HTTP server or whole-file buffering. */
final class SeekableAudio {
    interface Reader {int read(long offset,int size,byte[] data)throws Exception;}
    static ParcelFileDescriptor open(Context context,long length,Handler handler,Reader reader,Runnable release)throws IOException {
        return context.getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,new ProxyFileDescriptorCallback(){
            @Override public long onGetSize(){return length;}
            @Override public int onRead(long offset,int size,byte[] data)throws ErrnoException {
                if(offset<0)throw new ErrnoException("CloudStream read",OsConstants.EINVAL);
                if(offset>=length)return 0;
                try{int read=reader.read(offset,(int)Math.min(Math.min(size,256*1024),length-offset),data);if(read<=0)throw new IOException();return read;}
                catch(Exception failure){EngineLog.add("ERROR: Audio range could not be delivered ("+failure.getClass().getSimpleName()+")");throw new ErrnoException("CloudStream range read failed",OsConstants.EIO);}
            }
            @Override public void onRelease(){release.run();}
        },handler);
    }
}
