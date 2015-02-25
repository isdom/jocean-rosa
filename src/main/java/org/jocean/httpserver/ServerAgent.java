/**
 * 
 */
package org.jocean.httpserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;

import org.jocean.event.api.annotation.GuardPaired;
import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface ServerAgent {
    public interface ServerTask extends Detachable {
        @GuardPaired(paired={"org.jocean.netty.NettyUtils._NETTY_REFCOUNTED_GUARD"})
        public void sendHttpContent(final HttpContent httpContent);
    }
    
    public ServerTask createServerTask(final ChannelHandlerContext channelCtx, final HttpRequest httpRequest);
}
