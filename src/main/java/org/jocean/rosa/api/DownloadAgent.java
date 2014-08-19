package org.jocean.rosa.api;

import org.jocean.idiom.Detachable;
import org.jocean.rosa.spi.Downloadable;

public interface DownloadAgent {

    public interface DownloadTask extends Detachable {
        public <CTX, DOWNLOAD extends Downloadable> void start(
                final CTX ctx,
                final DOWNLOAD downloadable, 
                final DownloadReactor<CTX, DOWNLOAD> reactor, 
                final TransactionPolicy policy);
    }

    public interface DownloadReactor<CTX, DOWNLOAD extends Downloadable> {
        /**
         * transport layer actived for this download task
         */
        public void onTransportActived(final CTX ctx, final DOWNLOAD downloadable) throws Exception;

        /**
         * transport layer inactived for this download task
         * @throws Exception
         */
        public void onTransportInactived(final CTX ctx, final DOWNLOAD downloadable) throws Exception;

        /**
         * on content-type received, eg: "application/json" ...
         * @param contentType
         * @throws Exception
         */
        public void onContentTypeReceived(final CTX ctx, final DOWNLOAD downloadable, 
                final String contentType) 
                throws Exception;
        
        /**
         * download task in progress, maybe invoke more than once
         * @param currentByteSize: current fetched bytes
         * @param totalByteSize: total bytes for image
         */
        public void onProgress(final CTX ctx, final DOWNLOAD downloadable, 
                final long currentByteSize, final long totalByteSize) 
                throws Exception;

        /**
         * download succeed
         * @param blob : binary data
         */
        public void onDownloadSucceed(final CTX ctx, final DOWNLOAD downloadable) throws Exception;
        
        /**
         * download failed(timeout or received failed)
         */
        public void onDownloadFailure(final CTX ctx, final DOWNLOAD downloadable, 
                final int failureReason) throws Exception;
    }
    
    /**
     * create task for download
     * 
     * @return
     */
    public DownloadTask createDownloadTask();
    
    public void detachHttpClientOf(final DownloadTask task);
}
