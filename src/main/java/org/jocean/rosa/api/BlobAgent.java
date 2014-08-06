/**
 * 
 */
package org.jocean.rosa.api;

import java.net.URI;

import org.jocean.idiom.Detachable;
import org.jocean.idiom.block.Blob;

/**
 * @author isdom
 *
 */
public interface BlobAgent {
    
    public interface BlobTransaction extends Detachable {
        public <CTX> void start(final URI uri, final CTX ctx,
                final BlobReactor<CTX> reactor, final TransactionPolicy policy);
    }
    
    public interface BlobReactor<CTX> {
        /**
         * transport layer actived for this blob fetch action
         */
        public void onTransportActived(final CTX ctx) throws Exception;

        /**
         * transport layer inactived for this blob fetch action
         * @throws Exception
         */
        public void onTransportInactived(final CTX ctx) throws Exception;

        /**
         * on content-type received, eg: "application/json" ...
         * @param contentType
         * @throws Exception
         */
        public void onContentTypeReceived(final CTX ctx, final String contentType) throws Exception;
        
        /**
         * blob fetch action in progress, maybe invoke more than once
         * @param currentByteSize: current fetched bytes
         * @param totalByteSize: total bytes for image
         */
        public void onProgress(final CTX ctx, final long currentByteSize, final long totalByteSize) throws Exception;

        /**
         * blob fetched succeed
         * @param blob : binary data
         */
        public void onBlobReceived(final CTX ctx, final Blob blob) throws Exception;
        
        /**
         * blob fetch action failed(timeout or received failed)
         */
        public void onTransactionFailure(final CTX ctx, final int failureReason) throws Exception;
    }
    
	/**
	 * create transaction for blob fetch
	 * 
	 * @return
	 */
	public BlobTransaction createBlobTransaction();
}
