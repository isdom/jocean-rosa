/**
 * 
 */
package org.jocean.rosa.impl;

import io.netty.handler.codec.http.HttpResponse;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;

import javax.ws.rs.core.HttpHeaders;

import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.api.RemoteContentAgent;
import org.jocean.rosa.spi.Downloadable;
import org.jocean.rosa.spi.ObjectMemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONCreator;
import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class FileDownload implements Downloadable, Closeable, RemoteContentAgent.Content {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(FileDownload.class);
    
    public FileDownload(final String key, final URI uri, final File file) {
        this._key = key;
        this._uri = uri;
        this._file = file;
        this._downloadedSize = 0;
        this._etag = null;
        validDownloadedFile();
    }
    
    @JSONCreator
    public FileDownload(
            @JSONField(name="key")
            final String key, 
            @JSONField(name="uri")
            final URI uri, 
            @JSONField(name="downloadedFilename")
            final String filename,
            @JSONField(name="etag")
            final String etag,
            @JSONField(name="downloadedSize")
            final int downloadedSize,
            @JSONField(name="totalSize")
            final int totalSize,
            @JSONField(name="attachmentAsJson")
            final String attachmentJson
            ) {
        this._key = key;
        this._uri = uri;
        this._file = new File(filename);
        this._downloadedSize = downloadedSize;
        this._totalSize = totalSize;
        this._etag = etag;
        this._attachment = attachmentJson;
        validDownloadedFile();
    }
    
    private void validDownloadedFile() {
        if ( this._downloadedSize > 0 ) {
            if (!this._file.exists() ) {
                LOG.warn("downloaded file {} not exist, reset downloadedSize and Etag",  this._file);
                this._downloadedSize = 0;
                this._etag = null;
            }
            else {
                try {
                    final RandomAccessFile output = new RandomAccessFile(this._file, "rw");
                    if ( null != output ) {
                        try {
                            if ( this._downloadedSize != output.length() ) {
                                LOG.warn("downloaded file {}'s length not equals {}, reset downloaded content, downloadedSize and Etag",  
                                        this._file, this._downloadedSize);
                                output.setLength(0);
                                this._downloadedSize = 0;
                                this._etag = null;
                            }
                        }
                        finally {
                            output.close();
                        }
                    }
                }
                catch (Throwable e) {
                    LOG.warn("exception when test file {}, detail:{}", 
                            this._file, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }
    
    public FileDownload setPool(final BytesPool pool) {
        this._pool = pool;
        return this;
    }
    
    public FileDownload setMemo(final ObjectMemo memo) {
        this._memo = memo;
        return this;
    }
    
    @JSONField(name = "downloadedFilename")
    public String getDownloadedFilename() {
        try {
            return this._file.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }
    
    @JSONField(name="key")
    public String getKey() {
        return this._key;
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

    @JSONField(name="totalSize")
    public int getTotalSize() {
        return this._totalSize;
    }
    
    @JSONField(name="etag")
    @Override
    public String getEtag() {
        return this._etag;
    }
    
    @JSONField(name="attachmentAsJson")
    public String getAttachmentAsJson() {
        return this._attachment;
    }
    
    @JSONField(serialize=false)
    @Override
    public boolean isPartialDownload() {
        return this._downloadedSize > 0;
    }
    
    @JSONField(serialize=false)
    public boolean isDownloadComplete() {
        return (this._totalSize > 0 && this._totalSize == this._downloadedSize);
    }
    
    @JSONField(serialize=false)
    public File getFile() {
        return this._file;
    }

    @Override
    public int appendDownloadedContent(final Blob contentBlob) {
        initDownloadedFile();
        if ( null != this._output ) {
            return addDownloadedSize( (int)BlockUtils.blob2DataOutput(contentBlob, this._output, this._pool) );
        }
        else {
            return 0;
        }
    }
    
    public void saveToMemo() {
        this._memo.updateToMemo(this._key, this);
    }
    
    public void removeFromMemo() {
        this._memo.removeFromMemo(this._key);
    }

    private int addDownloadedSize(final int bytesAdded) {
        this._downloadedSize += bytesAdded;
        return bytesAdded;
    }
    
    private void initDownloadedFile() {
        if ( null == this._output ) {
            this._output = safeInitDownloadedFile();
        }
    }

    @Override
    public void resetDownloadedContent() {
        initDownloadedFile();
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
        this._totalSize = (int)HttpUtils.getContentTotalLengthFromResponseAsLong(response, -1);
    }

    @Override
    public void close() throws IOException {
        if ( null != this._output ) {
            this._output.close();
            this._output = null;
        }
    }
    
    @Override
    public <T> void saveAttachment(final T attachment) {
        this._attachment = JSON.toJSONString(attachment);
        saveToMemo();
    }

    @Override
    public <T> T loadAttachment(final Class<T> clazz) {
        if ( null == this._attachment ) {
            return null;
        }
        else {
            return JSON.parseObject(this._attachment, clazz);
        }
    }
    
    private final String _key;
    
    private final File _file;
    
    private transient RandomAccessFile _output = null; 
    
    private final URI _uri;
    
    private int _downloadedSize;
    
    private int _totalSize = -1;
    
    private String _etag;
    
    private String _attachment;
    
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
        return "FileDownload [_key=" + _key + ", _file=" + _file + ", _uri="
                + _uri + ", _downloadedSize=" + _downloadedSize
                + ", _totalSize=" + _totalSize + ", _etag=" + _etag
                + ", _attachment=" + _attachment + "]";
    }
}
