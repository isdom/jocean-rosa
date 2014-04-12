/**
 * 
 */
package org.jocean.rosa.impl;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.rosa.api.BusinessServerAgent;
import org.jocean.rosa.api.SignalTransaction;
import org.jocean.rosa.impl.flow.SignalTransactionFlow;
import org.jocean.transportclient.http.HttpStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class BusinessServerImpl implements BusinessServerAgent {

	private static final Logger LOG =
			LoggerFactory.getLogger("rose.impl.BusinessServerImpl");

	//	invoke in main UI thread
	@SuppressWarnings("unchecked")
    @Override
	public <REQUEST, RESPONSE> SignalTransaction<REQUEST, RESPONSE> 
		createSignalTransaction(final Class<REQUEST> reqCls, final Class<RESPONSE> respCls) {
		final URI uri = this._req2uri.get(reqCls);
		
		if ( null != uri ) {
			final SignalTransactionFlow<RESPONSE> flow = 
					new SignalTransactionFlow<RESPONSE>(this._stack, uri, respCls);
			_source.create(flow, flow.WAIT);
			
			return flow.queryInterfaceInstance(SignalTransaction.class);
		}
		else {
			return	null;
		}
	}

	public BusinessServerImpl(
	        final HttpStack httpStack, 
			final EventReceiverSource source) {
		this._stack = httpStack;
		this._source = source;
	}
	
	public BusinessServerImpl registerReuestType(final Class<?> reqCls, final URI uri) {
		this._req2uri.put(reqCls, uri);
		return this;
	}
	
	private final HttpStack _stack;
	private final EventReceiverSource _source;
	private final Map<Class<?>, URI> _req2uri = new HashMap<Class<?>, URI>();
}
