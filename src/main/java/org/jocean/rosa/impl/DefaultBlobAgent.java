/**
 * 
 */
package org.jocean.rosa.impl;

import java.net.URI;

import org.jocean.idiom.ExectionLoop;
import org.jocean.rosa.api.BlobAgent;
import org.jocean.rosa.api.BlobTransaction;
import org.jocean.rosa.api.HttpBodyPartRepo;
import org.jocean.rosa.impl.flow.BlobTransactionFlow;
import org.jocean.syncfsm.api.EventReceiverSource;
import org.jocean.transportclient.HttpStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DefaultBlobAgent implements BlobAgent {

	private static final Logger LOG =
			LoggerFactory.getLogger("rose.impl.DefaultBlobAgent");

    @Override
    public BlobTransaction createBlobTransaction(final URI uri) {
        if ( null != uri ) {
            final BlobTransactionFlow flow = 
                    new BlobTransactionFlow( this._stack, uri, this._partRepo);
            _source.create(flow, flow.WAIT, this._exectionLoop);
            
            return flow.getInterfaceAdapter(BlobTransaction.class);
        }
        else {
            return  null;
        }
    }

	public DefaultBlobAgent(final HttpStack httpStack, 
			final EventReceiverSource source, 
			final HttpBodyPartRepo repo, 
            final ExectionLoop exectionLoop) {
		this._stack = httpStack;
		this._source = source;
		this._partRepo = repo;
        this._exectionLoop = exectionLoop;
	}
	
	private final HttpStack _stack;
	private final EventReceiverSource _source;
	private final HttpBodyPartRepo _partRepo;
    private final ExectionLoop _exectionLoop;

}
