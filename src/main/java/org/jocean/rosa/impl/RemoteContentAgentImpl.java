package org.jocean.rosa.impl;

import java.io.File;
import java.net.URI;
import java.util.Collection;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.api.DownloadAgent;
import org.jocean.rosa.api.DownloadAgent.DownloadReactor;
import org.jocean.rosa.api.DownloadAgent.DownloadTask;
import org.jocean.rosa.api.RemoteContentAgent.Content;
import org.jocean.rosa.api.RemoteContentAgent;
import org.jocean.rosa.api.TransactionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jakewharton.disklrucache.DiskLruCache;

public class RemoteContentAgentImpl implements RemoteContentAgent {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(RemoteContentAgentImpl.class);
    
    public RemoteContentAgentImpl(
            final BytesPool bytesPool, 
            final DownloadAgent downloadAgent, 
            final String rootPath, 
            final long maxMetaSize) {
        this._bytesPool = bytesPool;
        this._diskCache = initDiskCacheBy(rootPath + "/meta", 1, maxMetaSize);
        this._memo = new DiskObjectMemo(this._diskCache);
        this._downloadAgent = downloadAgent;
        this._rootPath = rootPath;
    }
    
    @Override
    public <T extends Collection<Content>> T getAllContent(final T contents) {
        this._memo.getAll(contents, FileDownload.class);
        return contents;
    }
    
    @Override
    public Content getContentOf(final String fname) {
        return this._memo.get(fname, FileDownload.class);
    }

    public ContentTask createContentTask() {
        final DownloadTask task = _downloadAgent.createDownloadTask();
        
        return new ContentTask() {

            @Override
            public void detach() throws Exception {
                task.detach();
            }

            @Override
            public <CTX> void start(
                    final CTX ctx, 
                    final URI uri, 
                    final String fname,
                    final ContentReactor<CTX> reactor, 
                    final TransactionPolicy policy) {
                
                final FileDownload download = initDownloadable(uri, fname);
                
                if ( download.isDownloadComplete() ) {
                    try {
                        reactor.onFetchContentSucceed(ctx, download);
                    } catch (Exception e) {
                        LOG.warn("exception when onFetchSucceed for {}/{}/{}, detil:{}",
                                ctx, uri, fname, ExceptionUtils.exception2detail(e));
                    }
                    return;
                }
                
                task.start(ctx, download, new DownloadReactor<CTX, FileDownload>() {

                    @Override
                    public void onTransportActived(final CTX ctx,
                            final FileDownload downloadable) throws Exception {
                        reactor.onTransportActived(ctx, downloadable);
                    }

                    @Override
                    public void onTransportInactived(final CTX ctx,
                            final FileDownload downloadable) throws Exception {
                        reactor.onTransportInactived(ctx, downloadable);
                    }

                    @Override
                    public void onContentTypeReceived(final CTX ctx,
                            final FileDownload downloadable, final String contentType)
                            throws Exception {
                        reactor.onContentTypeReceived(ctx, downloadable, contentType);
                    }

                    @Override
                    public void onProgress(final CTX ctx, final FileDownload downloadable,
                            final long currentByteSize, final long totalByteSize)
                            throws Exception {
                        reactor.onProgress(ctx, downloadable, currentByteSize, totalByteSize);
                    }

                    @Override
                    public void onDownloadSucceed(final CTX ctx,
                            final FileDownload downloadable) throws Exception {
                        downloadable.close();
                        downloadable.saveToMemo();
                        reactor.onFetchContentSucceed(ctx, downloadable);
                    }

                    @Override
                    public void onDownloadFailure(CTX ctx,
                            FileDownload downloadable, int failureReason)
                            throws Exception {
                        downloadable.close();
                        downloadable.saveToMemo();
                        reactor.onFetchContentFailure(ctx, downloadable, failureReason);
                    }}, policy);
            }

            @Override
            public void detachHttpClient() {
                _downloadAgent.detachHttpClientOf(task);
            }};
    }
    
    /**
     * @param fname
     * @return
     */
    private FileDownload initDownloadable(final URI uri, final String fname) {
        FileDownload download = this._memo.get(fname, FileDownload.class);
        if ( null == download ) {
            download = new FileDownload(fname, uri, new File( this._rootPath + "/content/" + fname));
        }
        return download.setPool(this._bytesPool).setMemo(this._memo);
    }

    private static DiskLruCache initDiskCacheBy(
            final String path,
            final int appVersion,
            final long maxSize) {
        if (null == path) {
            return null;
        }
        final File cacheLocation = new File(path);

        DiskLruCache diskCache = null;
        try {
            diskCache = DiskLruCache.open(cacheLocation, appVersion, 1, maxSize);
        } catch (Throwable e) {
            LOG.warn("exception when create DiskLruCache for path {}, detil:{}",
                    path, ExceptionUtils.exception2detail(e));
        }
        return diskCache;
    }
    
    private final DiskLruCache _diskCache;
    private final BytesPool _bytesPool;
    private final DiskObjectMemo _memo;
    private final DownloadAgent _downloadAgent;
    private final String _rootPath;
}
