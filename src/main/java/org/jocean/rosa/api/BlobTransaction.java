/**
 * 
 */
package org.jocean.rosa.api;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface BlobTransaction extends Detachable {
	public void start(final BlobReactor reactor, final TransactionPolicy policy);
}
