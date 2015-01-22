/**
 * 
 */
package org.jocean.httpclient;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.HttpClientPool;
import org.jocean.httpclient.impl.Mediator;
import org.jocean.netty.NettyClient;

/**
 * @author isdom
 *
 */
public class HttpStack implements HttpClientPool {

//	private static final Logger LOG =
//			LoggerFactory.getLogger(HttpStack.class);

    public HttpStack(
            final EventReceiverSource source,
            final NettyClient client, 
            final int maxHttpConnectionCount) {
        this(source, source, client, maxHttpConnectionCount);
    }

    public HttpStack(
            final EventReceiverSource source4guide,
            final EventReceiverSource source4channel,
            final NettyClient client, 
            final int maxHttpConnectionCount) {
        this._mediator = new Mediator(source4guide, source4channel, client, maxHttpConnectionCount);
    }
    
    @Override
    public Guide createHttpClientGuide() {
        return this._mediator.createHttpClientGuide();
    }
    
    public int getMaxHttpConnectionCount() {
        return this._mediator.getMaxChannelCount();
    }
    
    public int getTotalHttpConnectionCount() {
        return this._mediator.getTotalChannelCount();
    }
    
    public int getBindedHttpConnectionCount() {
        return this._mediator.getBindedChannelCount();
    }
    
    public int getPendingGuideCount() {
        return this._mediator.getPendingGuideCount();
    }
    
    private final Mediator _mediator;
}
