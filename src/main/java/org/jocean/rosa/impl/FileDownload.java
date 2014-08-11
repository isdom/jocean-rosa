/**
 * 
 */
package org.jocean.rosa.impl;

import io.netty.handler.codec.http.HttpResponse;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.util.UUID;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.spi.Downloadable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class FileDownload implements Downloadable {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(FileDownload.class);
    
    public FileDownload(final URI uri, final File file, final BytesPool pool) {
        this._pool = pool;
        this._id = UUID.randomUUID().toString();
        this._uri = uri;
        this._file = file;
        this._downloadedSize = 0;
        this._etag = null;
    }
    
    public String getId() {
        return this._id;
    }

    public File getFile() {
        return this._file;
    }

    public URI getUri() {
        return this._uri;
    }

    public int getDownloadedSize() {
        return this._downloadedSize;
    }

    private int addDownloadedSize(final int bytesAdded) {
        this._downloadedSize += bytesAdded;
        return bytesAdded;
    }
    
    public String getETag() {
        return this._etag;
    }

    public boolean isPartialDownload() {
        return this._downloadedSize > 0;
    }
    
    @Override
    public int appendDownloadedContent(final Blob contentBlob) {
        validDownloadedFile();
        if ( null != this._output ) {
            return addDownloadedSize( (int)BlockUtils.blob2DataOutput(contentBlob, this._output, this._pool) );
        }
        else {
            return 0;
        }
    }

    /**
     * 
     */
    private void validDownloadedFile() {
        if ( null == this._output ) {
            this._output = safeInitDownloadedFile();
        }
    }

    @Override
    public void resetDownloadedContent() {
        validDownloadedFile();
        try {
            this._output.setLength(0);
        } catch (IOException e) {
            LOG.warn("exception when RandomAccessFile.setLength for {}, detail:{}", 
                    this, ExceptionUtils.exception2detail(e));
        }
        this._downloadedSize = 0;
    }
    
    @Override
    public void updateResponse(final HttpResponse response) {
        
    }

    private final BytesPool _pool;
    
    private final String _id;
    
    private final File _file;
    
    private RandomAccessFile _output = null; 
    
    private final URI _uri;
    
    private int _downloadedSize;
    
    private String _etag;
    
    /**
     * @param description
     */
    private RandomAccessFile safeInitDownloadedFile() {
        if ( !this._file.exists() ) {
            final File parentDir = this._file.getParentFile();
            parentDir.mkdirs();
        }
        RandomAccessFile downloadedFile = null;
        try {
            downloadedFile = new RandomAccessFile(this._file, "rw");
            if ( null !=  downloadedFile ) {
                downloadedFile.seek(this.getDownloadedSize());
            }
            return downloadedFile;
        } catch (Throwable e) {
            LOG.error("exception when init RandomAccessFile for {}, detail:{}", 
                    this, ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    @Override
    public String toString() {
        return "FileDownload [id=" + this._id + ", file=" + this._file
                + ", uri=" + this._uri + "]";
    }
}
