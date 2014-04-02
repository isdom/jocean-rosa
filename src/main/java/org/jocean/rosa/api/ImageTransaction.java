/**
 * 
 */
package org.jocean.rosa.api;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface ImageTransaction extends Detachable {
	public void start(final ImageReactor reactor, final TransactionPolicy policy);
}
