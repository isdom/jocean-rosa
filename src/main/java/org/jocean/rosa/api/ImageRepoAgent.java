/**
 * 
 */
package org.jocean.rosa.api;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface ImageRepoAgent {
	/**
	 * create transaction for image fetch via special uri
	 * 
	 * @param uri: uri for fetch image
	 * @param maxRetryCount: if transport failed ( bcs poor GPRS link), re-try count, -1 means retry forever
	 * @return
	 */
	public ImageTransaction createImageTransaction(final URI uri);
}
