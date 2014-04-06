/**
 * 
 */
package org.jocean.rosa.api;

import java.net.URI;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface BlobTransaction extends Detachable {
	public void start(final URI uri, final BlobReactor reactor, final TransactionPolicy policy);
}
