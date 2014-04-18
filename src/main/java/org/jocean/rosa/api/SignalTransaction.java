/**
 * 
 */
package org.jocean.rosa.api;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface SignalTransaction extends Detachable {
	public <REQUEST, RESPONSE> void start(
	        final REQUEST request, 
	        final Class<RESPONSE> respCls,
	        final SignalReactor<RESPONSE> reactor, 
	        final TransactionPolicy policy);
}
