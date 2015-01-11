/**
 * 
 */
package org.jocean.httpclient.api;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import org.jocean.event.api.annotation.GuardPaired;
import org.jocean.event.api.annotation.GuardReferenceCounted;
import org.jocean.idiom.block.Blob;

/**
 * @author isdom
 *
 */
public interface HttpClient {
	
    public interface HttpReactor<CTX> {
        
        @GuardPaired(paired={"org.jocean.netty.NettyUtils._NETTY_REFCOUNTED_GUARD"})
        public void onHttpResponseReceived(final CTX ctx, final HttpResponse response) 
                throws Exception;

        @GuardReferenceCounted
        public void onHttpContentReceived(final CTX ctx, final Blob blob) 
                throws Exception;

        @GuardReferenceCounted
        public void onLastHttpContentReceived(final CTX ctx, final Blob blob) 
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
