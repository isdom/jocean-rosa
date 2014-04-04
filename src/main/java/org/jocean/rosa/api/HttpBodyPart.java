/**
 * 
 */
package org.jocean.rosa.api;

import io.netty.handler.codec.http.HttpResponse;

import java.util.Collection;

/**
 * @author isdom
 *
 */
public class HttpBodyPart {
	
	public HttpBodyPart(final HttpResponse response, final Blob blob) {
		this._response = response;
		this._blob = blob;
	}
	
	public HttpResponse httpResponse() {
		return this._response;
	}
	
	public Collection<byte[]> parts() {
		return this._blob.bytesCollection();
	}
	
    public Blob blob() {
        return this._blob;
    }
    
	public int byteCount() {
		return this._blob.length();
	}
	
	private final HttpResponse _response;
	private final Blob _blob;
}
