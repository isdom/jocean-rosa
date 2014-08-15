package org.jocean.rosa.api;

import java.io.File;
import java.net.URI;
import java.util.Collection;

import org.jocean.idiom.Detachable;

public interface RemoteContentAgent {
    
    public interface Content {
        public URI getUri();
        
        public File getFile();
        
        public int getDownloadedSize();
        
        public boolean isDownloadComplete();
        
        public <T> void saveAttachment(final T attachment);
        
        public <T> T loadAttachment(final Class<T> clazz);
    }
    
    public interface ContentTask extends Detachable {
        public <CTX> void start(
                final CTX ctx,
                final URI uri, 
                final String fname,
                final ContentReactor<CTX> reactor, 
                final TransactionPolicy policy);
    }

    public interface ContentReactor<CTX> {
        /**
         * transport layer actived for this download task
         */
        public void onTransportActived(final CTX ctx, final Content content) throws Exception;

        /**
         * transport layer inactived for this download task
         * @throws Exception
         */
        public void onTransportInactived(final CTX ctx, final Content content) throws Exception;

        /**
         * on content-type received, eg: "application/json" ...
         * @param contentType
         * @throws Exception
         */
        public void onContentTypeReceived(final CTX ctx, final Content content, 
                final String contentType) 
                throws Exception;
        
        /**
         * download task in progress, maybe invoke more than once
         * @param currentByteSize: current fetched bytes
         * @param totalByteSize: total bytes for image
         */
        public void onProgress(final CTX ctx, final Content content, 
                final long currentByteSize, final long totalByteSize) 
                throws Exception;

        /**
         * fetch content succeed
         * @param blob : binary data
         */
        public void onFetchContentSucceed(final CTX ctx, final Content content) throws Exception;
        
        /**
         * fetch content failed(timeout or received failed)
         */
        public void onFetchContentFailure(final CTX ctx, final Content Content, 
                final int failureReason) throws Exception;
    }
    
    /**
     * create task for fetch content
     * 
     * @return
     */
    public ContentTask createContentTask();
    
    public <T extends Collection<Content>> T getAllContent(final T contents);
    
    public Content getContentOf(final String fname);
}
