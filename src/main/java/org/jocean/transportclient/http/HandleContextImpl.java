/**
 * 
 */
package org.jocean.transportclient.http;

import java.net.URI;

import org.jocean.transportclient.api.HttpClientHandle;

/**
 * @author isdom
 *
 */
class HandleContextImpl<OWNER> implements HttpClientHandle.Context {

    public HandleContextImpl(final HttpClientHandle.Context ctx, final OWNER owner) {
        this._priority = ctx.priority();
        this._uri = ctx.uri();
        this._owner = owner;
    }
    
    @Override
    public int priority() {
        return this._priority;
    }

    @Override
    public URI uri() {
        return this._uri;
    }
    
    public OWNER owner() {
        return this._owner;
    }
    
    @Override
    public String toString() {
        return "handlectx [priority=" + _priority
                + ", uri=" + _uri + "]";
    }

    private final int _priority;
    private final URI _uri;
    private final OWNER _owner;
}
