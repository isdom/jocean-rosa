/**
 * 
 */
package org.jocean.httpclient.api;

import org.jocean.idiom.block.Blob;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * @author isdom
 *
 */
public interface HttpClient {
	
    public interface HttpReactor<CTX> {
        
        public void onHttpResponseReceived(final CTX ctx, final HttpResponse response) 
                throws Exception;

        public void onHttpContentReceived(final CTX ctx, final Blob blob) 
                throws Exception;

        public void onLastHttpContentReceived(final CTX ctx, final Blob blob) 
                throws Exception;
    }
    
    /**
	 * 发送 Http Request
	 * @param request
	 */
	public <CTX> void sendHttpRequest(final CTX ctx, final HttpRequest request, final HttpReactor<CTX> reactor) 
	        throws Exception;
}
