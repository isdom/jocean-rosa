/**
 * 
 */
package org.jocean.rosa.impl;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Md5;
import org.jocean.rosa.spi.ObjectMemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.jakewharton.disklrucache.DiskLruCache;
import com.jakewharton.disklrucache.DiskLruCache.Editor;
import com.jakewharton.disklrucache.DiskLruCache.Snapshot;

/**
 * @author isdom
 *
 */
public class DiskObjectMemo implements ObjectMemo {

    private static final Logger LOG = 
            LoggerFactory.getLogger(DiskObjectMemo.class);
    
    public DiskObjectMemo(final DiskLruCache diskCache) {
        this._diskCache = diskCache;
    }
    
    @Override
    public void updateToMemo(final String key, final Object obj) {
        final String diskCacheKey = Md5.encode(key);
        try {
            final Editor editor = this._diskCache.edit(diskCacheKey);
            OutputStream os = null;
            if ( null != editor ) {
                try {
                    os = editor.newOutputStream(0);
                    final String json = JSON.toJSONString(obj);
                    if ( null != os && null != json ) {
                        final DataOutputStream output = new DataOutputStream(os);
                        output.writeUTF(json);
                        output.flush();
                    }
                }
                finally {
                    if ( null != os ) {
                        try {
                            os.close();
                        }
                        catch (Throwable e) {
                        }
                    }
                    editor.commit();
                }
            }
        }
        catch (Throwable e) {
            LOG.warn("exception when updateToMemo for {}/{}, detail: {}", 
                    key, obj, ExceptionUtils.exception2detail(e));
        }
    }

    @Override
    public void removeFromMemo(final String key) {
        try {
            this._diskCache.remove(Md5.encode(key));
        }
        catch (Throwable e) {
            LOG.warn("exception when removeFromMemo for {}, detail: {}", 
                    key, ExceptionUtils.exception2detail(e));
        }
    }
    
    public <T> T  get(final String key, final Class<T> clazz) {
        try {
            return getInternal(Md5.encode(key), clazz);
        }
        catch (Throwable e) {
            LOG.warn("exception when get for {}, detail: {}", 
                    key, ExceptionUtils.exception2detail(e));
        }
        
        return null;
    }

    /**
     * @param key
     * @param clazz
     * @param diskCacheKey
     * @return
     */
    private <T> T getInternal(final String diskCacheKey, final Class<T> clazz) 
            throws Exception {
        final Snapshot snapshot = getSnapshotFromDiskCache(diskCacheKey);
        InputStream is = null;
        if ( null != snapshot ) {
            try {
                is = snapshot.getInputStream(0);
                if ( null != is ) {
                    final DataInput input = new DataInputStream(is);
                    final String json = input.readUTF();
                    if ( null != json ) {
                        return JSON.parseObject(json, clazz);
                    }
                }
            }
            finally {
                if ( null != is ) {
                    try {
                        is.close();
                    }
                    catch (Throwable e) {
                    }
                }
                snapshot.close();
            }
        }
        return null;
    }
    
    public <T> void getAll(final Collection<T> objs, final Class<? extends T> clazz) {
        final List<String> keys = new ArrayList<String>();
        
        getAllKeys(keys);
        for ( String key : keys ) {
            try {
                final T obj = getInternal(key, clazz);
                if ( null != obj ) {
                    objs.add(obj);
                }
            }
            catch (Throwable e) {
                LOG.warn("exception when getAll for {}, detail: {}", 
                        key, ExceptionUtils.exception2detail(e));
            }
        }
    }

    /**
     * @param keys
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private void getAllKeys(final List<String> keys) {
        try {
            final Field entriesField = this._diskCache.getClass().getDeclaredField("lruEntries");
            entriesField.setAccessible(true);
            final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>)entriesField.get(this._diskCache);
            keys.addAll( entries.keySet() );
        } catch (Throwable e) {
            LOG.warn("exception when getAllKeys, detail: {}", 
                    ExceptionUtils.exception2detail(e));
        }
    }
    
    private Snapshot getSnapshotFromDiskCache(final String diskCacheKey) {
        try {
            if ( null != this._diskCache ) {
                return this._diskCache.get(diskCacheKey);
            }
        }
        catch (Throwable e) {
            LOG.warn("exception when LruDiskCache.get Snapshot for diskCacheKey({}), detail:{}",
                    diskCacheKey, ExceptionUtils.exception2detail(e));
        }
        return null;
    }
    
    private final DiskLruCache _diskCache;

}
