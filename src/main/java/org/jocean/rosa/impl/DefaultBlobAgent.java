/**
 * 
 */
package org.jocean.rosa.impl;

import org.jocean.event.api.EventEngine;
import org.jocean.httpclient.api.GuideBuilder;
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
    public BlobTask createBlobTask() {
        final BlobTransactionFlow flow = 
                new BlobTransactionFlow( this._pool, this._guideBuilder, this._partRepo);
        _engine.create(flow, flow.WAIT);
        
        return flow.queryInterfaceInstance(BlobTask.class);
    }

	public DefaultBlobAgent(
	        final BytesPool pool,
	        final GuideBuilder guideBuilder, 
			final EventEngine engine, 
			final HttpBodyPartRepo repo) {
	    this._pool = pool;
		this._guideBuilder = guideBuilder;
		this._engine = engine;
		this._partRepo = repo;
	}
	
	private final BytesPool _pool;
	private final GuideBuilder _guideBuilder;
	private final EventEngine _engine;
	private final HttpBodyPartRepo _partRepo;
}
