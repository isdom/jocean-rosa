/**
 * 
 */
package org.jocean.rosa.api;

/**
 * @author isdom
 *
 */
public interface BlobReactor {
	/**
	 * transport layer actived for this blob fetch action
	 */
	public void onTransportActived() throws Exception;

	/**
	 * transport layer inactived for this blob fetch action
	 * @throws Exception
	 */
	public void onTransportInactived() throws Exception;

    /**
     * on content-type received, eg: "application/json" ...
     * @param contentType
     * @throws Exception
     */
    public void onContentTypeReceived(final String contentType) throws Exception;
	
	/**
	 * blob fetch action in progress, maybe invoke more than once
	 * @param currentByteSize: current fetched bytes
	 * @param totalByteSize: total bytes for image
	 */
	public void onProgress(final long currentByteSize, final long totalByteSize) throws Exception;

	/**
	 * blob fetched succeed
	 * @param blob : binary data
	 */
	public void onBlobReceived(final Blob blob) throws Exception;
	
	/**
	 * blob fetch action failed(timeout or received failed)
	 */
	public void onTransactionFailure(final int failureReason) throws Exception;

}
