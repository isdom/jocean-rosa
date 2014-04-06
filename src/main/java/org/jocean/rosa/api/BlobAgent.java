/**
 * 
 */
package org.jocean.rosa.api;

/**
 * @author isdom
 *
 */
public interface BlobAgent {
	/**
	 * create transaction for blob fetch via special uri
	 * 
	 * @param uri: uri for fetch blob
	 * @return
	 */
	public BlobTransaction createBlobTransaction();
}
