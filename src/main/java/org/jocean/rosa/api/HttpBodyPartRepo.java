/**
 * 
 */
package org.jocean.rosa.api;

import java.net.URI;

/**
 * @author isdom
 *
 */
public interface HttpBodyPartRepo {
	public void put(final URI uri, final HttpBodyPart body) throws Exception;
	public HttpBodyPart get(final URI uri) throws Exception;
	public void remove(final URI uri) throws Exception;
}
