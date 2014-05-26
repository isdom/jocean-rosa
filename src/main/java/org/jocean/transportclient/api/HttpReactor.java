/**
 * 
 */
package org.jocean.transportclient.api;

import io.netty.handler.codec.http.HttpResponse;

import org.jocean.idiom.block.Blob;

/**
 * @author isdom
 *
 */
public interface HttpReactor {
	
	public void onHttpClientObtained(final HttpClient httpClient) throws Exception;
	
	public void onHttpClientLost() throws Exception;
	
	public void onHttpResponseReceived(final HttpResponse response) throws Exception;

	public void onHttpContentReceived(final Blob blob) throws Exception;

	public void onLastHttpContentReceived(final Blob blob) throws Exception;
}
