/**
 * 
 */
package org.jocean.rosa.impl;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.api.HttpClientPool;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.api.BlobAgent;
import org.jocean.rosa.api.HttpBodyPartRepo;
import org.jocean.rosa.impl.flow.BlobTransactionFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DefaultBlobAgent implements BlobAgent {

	@SuppressWarnings("unused")
    private static final Logger LOG =
			LoggerFactory.getLogger(DefaultBlobAgent.class);

    @Override
    public BlobTransaction createBlobTransaction() {
        final BlobTransactionFlow flow = 
                new BlobTransactionFlow( this._pool, this._clientPool, this._partRepo);
        _source.create(flow, flow.WAIT);
        
        return flow.queryInterfaceInstance(BlobTransaction.class);
    }

	public DefaultBlobAgent(
	        final BytesPool pool,
	        final HttpClientPool clientPool, 
			final EventReceiverSource source, 
			final HttpBodyPartRepo repo) {
	    this._pool = pool;
		this._clientPool = clientPool;
		this._source = source;
		this._partRepo = repo;
	}
	
	private final BytesPool _pool;
	private final HttpClientPool _clientPool;
	private final EventReceiverSource _source;
	private final HttpBodyPartRepo _partRepo;
}
