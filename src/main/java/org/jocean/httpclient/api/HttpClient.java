/**
 * 
 */
package org.jocean.httpclient.api;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

import org.jocean.event.api.annotation.GuardPaired;

/**
 * @author isdom
 *
 */
public interface HttpClient {
	
    public interface HttpReactor<CTX> {
        
        @GuardPaired(paired={"org.jocean.netty.NettyUtils._NETTY_REFCOUNTED_GUARD"})
        public void onHttpResponseReceived(final CTX ctx, final HttpResponse response) 
                throws Exception;
        
        @GuardPaired(paired={"org.jocean.netty.NettyUtils._NETTY_REFCOUNTED_GUARD"})
        public void onHttpContentReceived(final CTX ctx, final HttpContent content) 
                throws Exception;

        @GuardPaired(paired={"org.jocean.netty.NettyUtils._NETTY_REFCOUNTED_GUARD"})
        public void onLastHttpContentReceived(final CTX ctx, final LastHttpContent content) 
                throws Exception;
    }
    
    /**
	 * 发送 Http Request
	 * @param request
	 */
    @GuardPaired(paired={"org.jocean.netty.NettyUtils._NETTY_REFCOUNTED_GUARD"})
	public <CTX> void sendHttpRequest(final CTX ctx, final HttpRequest request, final HttpReactor<CTX> reactor) 
	        throws Exception;

    /**
     * 发送 Http Content
     * @param content
     */
    @GuardPaired(paired={"org.jocean.netty.NettyUtils._NETTY_REFCOUNTED_GUARD"})
    public void sendHttpContent(final HttpContent content) 
            throws Exception;
}
