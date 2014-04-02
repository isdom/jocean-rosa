/**
 * 
 */
package org.jocean.rosa.impl;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.jocean.idiom.ExectionLoop;
import org.jocean.rosa.api.BusinessServerAgent;
import org.jocean.rosa.api.SignalTransaction;
import org.jocean.rosa.impl.flow.SignalTransactionFlow;
import org.jocean.syncfsm.api.EventReceiverSource;
import org.jocean.transportclient.HttpStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.os.Handler;

/**
 * @author isdom
 *
 */
public class BusinessServerImpl implements BusinessServerAgent {

	private static final Logger LOG =
			LoggerFactory.getLogger("rose.impl.BusinessServerImpl");

	//	invoke in main UI thread
	@Override
	public <REQUEST, RESPONSE> SignalTransaction<REQUEST, RESPONSE> 
		createSignalTransaction(final Class<REQUEST> reqCls, final Class<RESPONSE> respCls) {
		final URI uri = this._req2uri.get(reqCls);
		
		if ( null != uri ) {
			final SignalTransactionFlow<RESPONSE> flow = 
					new SignalTransactionFlow<RESPONSE>(this._stack, uri, respCls);
			_source.create(flow, flow.WAIT, this._exectionLoop);
			
			return flow.getInterfaceAdapter(SignalTransaction.class);
		}
		else {
			return	null;
		}
	}

	public BusinessServerImpl(final HttpStack httpStack, 
			final EventReceiverSource source, 
			final Handler handler) {
		this._stack = httpStack;
		this._source = source;
		this._handler = handler;
		this._exectionLoop = new HandlerExectionLoop(this._handler);
	}
	
	public BusinessServerImpl registerReuestType(final Class<?> reqCls, final URI uri) {
		this._req2uri.put(reqCls, uri);
		return this;
	}
	
	private final HttpStack _stack;
	private final EventReceiverSource _source;
	private final Handler _handler;
	private final Map<Class<?>, URI> _req2uri = new HashMap<Class<?>, URI>();
	private final ExectionLoop _exectionLoop;
}
