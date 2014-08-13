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

import javax.ws.rs.core.HttpHeaders;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.spi.ObjectMemo;
import org.jocean.rosa.spi.Downloadable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.annotation.JSONCreator;
import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class FileDownload implements Downloadable {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(FileDownload.class);
    
    public FileDownload(final URI uri, final File file) {
        this._id = UUID.randomUUID().toString();
        this._uri = uri;
        this._file = file;
        this._downloadedSize = 0;
        this._etag = null;
    }
    
    @JSONCreator
    public FileDownload(
            @JSONField(name="id")
            final String id,
            @JSONField(name="uri")
            final URI uri, 
            @JSONField(name="downloadedFile")
            final String filePath,
            @JSONField(name="etag")
            final String etag,
            @JSONField(name="downloadedSize")
            final int downloadedSize
            ) {
        this._id = id;
        this._uri = uri;
        this._file = new File(filePath);
        this._downloadedSize = downloadedSize;
        this._etag = etag;
    }
    
    public FileDownload setPool(final BytesPool pool) {
        this._pool = pool;
        return this;
    }
    
    public FileDownload setMemo(final ObjectMemo memo) {
        this._memo = memo;
        return this;
    }
    
    @JSONField(name = "id")
    public String getId() {
        return this._id;
    }

    @JSONField(name = "downloadedFile")
    public String getDownloadedFile() {
        try {
            return this._file.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }
    
    @JSONField(name="uri")
    @Override
    public URI getUri() {
        return this._uri;
    }

    @JSONField(name="downloadedSize")
    @Override
    public int getDownloadedSize() {
        return this._downloadedSize;
    }

    @JSONField(name="etag")
    @Override
    public String getEtag() {
        return this._etag;
    }
    
    @JSONField(serialize=false)
    @Override
    public boolean isPartialDownload() {
        return this._downloadedSize > 0;
    }
    
    @JSONField(serialize=false)
    public File getFile() {
        return this._file;
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
    
    public void saveToMemo() {
        this._memo.updateToMemo(this._uri.toASCIIString(), this);
    }
    
    public void removeFromMemo() {
        this._memo.removeFromMemo(this._uri.toASCIIString());
    }

    private int addDownloadedSize(final int bytesAdded) {
        this._downloadedSize += bytesAdded;
        return bytesAdded;
    }
    
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
        this._etag = response.headers().get(HttpHeaders.ETAG);
    }

    private final String _id;
    
    private final File _file;
    
    private transient RandomAccessFile _output = null; 
    
    private final URI _uri;
    
    private int _downloadedSize;
    
    private String _etag;
    
    private transient BytesPool _pool;
    
    private transient ObjectMemo _memo;
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
        return "FileDownload [_id=" + _id + ", _file=" + _file + ", _uri="
                + _uri + ", _downloadedSize=" + _downloadedSize + ", _etag="
                + _etag + "]";
    }
}
