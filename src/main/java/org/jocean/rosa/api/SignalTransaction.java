/**
 * 
 */
package org.jocean.rosa.api;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface SignalTransaction<REQUEST, RESPONSE> extends Detachable {
	public void start(final REQUEST request, final SignalReactor<RESPONSE> reactor, final TransactionPolicy policy);
}
