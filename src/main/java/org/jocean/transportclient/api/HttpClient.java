/**
 * 
 */
package org.jocean.transportclient.api;

import io.netty.handler.codec.http.HttpRequest;

/**
 * @author isdom
 *
 */
public interface HttpClient {
	
	/**
	 * 发送 Http Request，该方法应该在 netty eventloop 中被调用，可使用 TransportClient.eventloop() 获取
	 * @param request
	 */
	public	void sendHttpRequest(final HttpRequest request) throws Exception;
}
