/**
 * 
 */
package org.jocean.netty;

import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
//import io.netty.buffer.AlwaysUnpooledHeapByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;

import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExectionLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class NettyClient {
	
	private static final Logger LOG =
			LoggerFactory.getLogger(NettyClient.class);
	
    public NettyClient() {
        this(1);
    }
    
	public NettyClient(final int processThreadNumber) {
        // Configure the client.
    	this._bootstrap = new Bootstrap()
    		.group(new NioEventLoopGroup(processThreadNumber))
    		.channel(NioSocketChannel.class)
    		.handler(new ChannelInitializer<Channel>() {

				@Override
				protected void initChannel(Channel ch) throws Exception {
				}});
    	final EventLoop eventloop = eventLoop();
    	this._exectionLoop = new ExectionLoop() {

            @Override
            public boolean inExectionLoop() {
                return eventloop.inEventLoop();
            }

            @Override
            public Detachable submit(Runnable runnable) {
                final Future<?> future = eventloop.submit(runnable);
                return new Detachable() {
                    @Override
                    public void detach() {
                        future.cancel(false);
                    }};
            }

            @Override
            public Detachable schedule(Runnable runnable, long delayMillis) {
                final Future<?> future = eventloop.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
                return new Detachable() {
                    @Override
                    public void detach() {
                        future.cancel(false);
                    }};
            }};
	}
	
	private EventLoop eventLoop() {
		return this._bootstrap.group().next();
	}
	
	public ExectionLoop exectionLoop() {
	    return this._exectionLoop;
	}
	
	public void destroy() {
        // Shut down executor threads to exit.
        this._bootstrap.group().shutdownGracefully();
	}
	
	public Channel newChannel() {
		final Channel ch = this._bootstrap.register().channel();
		//ch.config().setAllocator(new AlwaysUnpooledHeapByteBufAllocator() );
		if ( LOG.isTraceEnabled() ) {
			LOG.trace("create new channel: {}", ch);
		}
		return	ch;
	}
	
    private final Bootstrap _bootstrap;
    private final ExectionLoop _exectionLoop;
}
