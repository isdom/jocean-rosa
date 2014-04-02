/**
 * 
 */
package org.jocean.rosa.impl;

import java.net.URI;

import org.jocean.idiom.ExectionLoop;
import org.jocean.rosa.api.ApiUtils;
import org.jocean.rosa.api.HttpBodyPartRepo;
import org.jocean.rosa.api.ImageRepoAgent;
import org.jocean.rosa.api.ImageTransaction;
import org.jocean.rosa.impl.flow.ImageTransactionFlow;
import org.jocean.syncfsm.api.EventReceiverSource;
import org.jocean.transportclient.HttpStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.os.Handler;

/**
 * @author isdom
 *
 */
public class ImageRepoImpl implements ImageRepoAgent {

	private static final Logger LOG =
			LoggerFactory.getLogger("rose.impl.ImageRepoImpl");

	@Override
	public ImageTransaction createImageTransaction(final URI uri) {
		if ( null != uri ) {
			final ImageTransactionFlow flow = 
					new ImageTransactionFlow( this._stack, uri, this._partRepo);
			_source.create(flow, flow.WAIT, this._exectionLoop);
			
			return flow.getInterfaceAdapter(ImageTransaction.class);
		}
		else {
			return	null;
		}
	}


	public ImageRepoImpl(final HttpStack httpStack, 
			final EventReceiverSource source, 
			final Handler handler,
			final HttpBodyPartRepo repo) {
		this._stack = httpStack;
		this._source = source;
		this._handler = handler;
		this._partRepo = repo;
        this._exectionLoop = ApiUtils.genExectionLoopOf(this._handler);
	}
	
	private final HttpStack _stack;
	private final EventReceiverSource _source;
	private final Handler _handler;
	private final HttpBodyPartRepo _partRepo;
    private final ExectionLoop _exectionLoop;
}
