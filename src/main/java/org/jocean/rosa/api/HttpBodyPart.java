/**
 * 
 */
package org.jocean.rosa.api;

import io.netty.handler.codec.http.HttpResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author isdom
 *
 */
public class HttpBodyPart {
	
	public HttpBodyPart(final HttpResponse response, final List<byte[]> bytesList) {
		this._response = response;
		this._bytesList.addAll( bytesList );
	}
	
	public HttpResponse getHttpResponse() {
		return this._response;
	}
	
	public Collection<byte[]> getParts() {
		return Collections.unmodifiableCollection( this._bytesList );
	}
	
	public int byteCount() {
		int totalSize = 0;
		for ( byte[] bytes : this._bytesList) {
			totalSize += bytes.length;
		}
		
		return totalSize;
	}
	
	private final HttpResponse _response;
	private final List<byte[]> _bytesList = new ArrayList<byte[]>();
}
