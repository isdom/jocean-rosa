/**
 * 
 */
package org.jocean.httpclient;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.impl.MediatorFlow;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.netty.NettyClient;

/**
 * @author isdom
 *
 */
public class HttpStack {

//	private static final Logger LOG =
//			LoggerFactory.getLogger(HttpStack.class);

	private final MediatorFlow _mediator;
	
    public HttpStack(
            final BytesPool bytesPool,
            final EventReceiverSource source,
            final NettyClient client, 
            final int maxActived) {
        this._mediator = new MediatorFlow(bytesPool, source, client, maxActived);
        source.create(this._mediator, this._mediator.DISPATCH);
    }

    public Guide createHttpClientGuide() {
        return this._mediator.createHttpClientGuide();
    }
}
