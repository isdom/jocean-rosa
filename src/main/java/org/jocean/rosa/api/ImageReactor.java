/**
 * 
 */
package org.jocean.rosa.api;

import android.graphics.Bitmap;

/**
 * @author isdom
 *
 */
public interface ImageReactor {
	/**
	 * transport layer actived for this image fetch action
	 */
	public void onTransportActived() throws Exception;

	/**
	 * transport layer inactived for this image fetch action
	 * @throws Exception
	 */
	public void onTransportInactived() throws Exception;
	
	/**
	 * image fetch action in progress, maybe invoke more than once
	 * @param currentByteSize: current fetched bytes
	 * @param totalByteSize: total bytes for image
	 */
	public void onProgress(final long currentByteSize, final long totalByteSize) throws Exception;

	/**
	 * image fetched succeed, and decode as bitmap
	 * @param bitmap decoded bitmap
	 */
	public void onImageReceived(final Bitmap bitmap) throws Exception;
	
	/**
	 * image fetch action finished, succeed or failed(timeout or decode failed)
	 */
	public void onTransactionFinished(final int status) throws Exception;

}
