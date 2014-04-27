/**
 * 
 */
package org.jocean.rosa.api;

import io.netty.handler.codec.http.HttpResponse;

import org.jocean.idiom.AbstractReferenceCounted;
import org.jocean.idiom.Blob;

/**
 * @author isdom
 *
 */
public class HttpBodyPart extends AbstractReferenceCounted<HttpBodyPart> {
	
	public HttpBodyPart(final HttpResponse response, final Blob blob) {
		this._response = response;
		this._blob = blob.retain();
	}
	
	public HttpResponse httpResponse() {
		return this._response;
	}
	
    public Blob blob() {
        return this._blob;
    }
    
	public int byteCount() {
		return this._blob.length();
	}
	
	
    @Override
    protected void deallocate() {
        this._blob.release();
    }
	
	private final HttpResponse _response;
	private final Blob _blob;
}
