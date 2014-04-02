/**
 * 
 */
package org.jocean.rosa.api;

/**
 * @author isdom
 *
 */
public interface BusinessServerAgent {
	public <REQUEST,RESPONSE> SignalTransaction<REQUEST, RESPONSE> 
		createSignalTransaction(final Class<REQUEST> reqCls, final Class<RESPONSE> respCls);
}
