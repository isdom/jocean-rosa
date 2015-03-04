/**
 * 
 */
package org.jocean.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author isdom
 *
 */
public interface HttpRequestTransformer {
    public interface Builder {
        public HttpRequestTransformer build(final HttpRequest httpRequest);
    }
    
    public FullHttpRequest transform(final HttpRequest httpRequest, final ByteBuf content);
}
