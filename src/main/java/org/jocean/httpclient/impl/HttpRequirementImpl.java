/**
 * 
 */
package org.jocean.httpclient.impl;

import java.net.URI;

import org.jocean.httpclient.api.Guide;

/**
 * @author isdom
 *
 */
class HttpRequirementImpl<OWNER> implements Guide.Requirement {

    public HttpRequirementImpl(final Guide.Requirement requirement, final OWNER owner) {
        this._priority = requirement.priority();
        this._uri = requirement.uri();
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
        return "handleRequirement [priority=" + _priority
                + ", uri=" + _uri + "]";
    }

    private final int _priority;
    private final URI _uri;
    private final OWNER _owner;
}
