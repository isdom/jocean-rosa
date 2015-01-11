/**
 * 
 */
package org.jocean.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.PairedGuardEventable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public abstract class EventReceiverInboundHandler<I> extends
		SimpleChannelInboundHandler<I> {
	
	private static final Logger LOG =
			LoggerFactory.getLogger(EventReceiverInboundHandler.class);
	
    private static final PairedGuardEventable CHANNEL_MESSAGERECEIVED_EVENT = 
            new PairedGuardEventable(NettyUtils._NETTY_REFCOUNTED_GUARD, NettyEvents.CHANNEL_MESSAGERECEIVED);
    
	public EventReceiverInboundHandler(final EventReceiver receiver) {
		this._receiver = receiver;
	}
	
    @Override
	public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("channelRegistered: ch({})", ctx.channel());
        }
        
		super.channelRegistered(ctx);
		this._receiver.acceptEvent(NettyEvents.CHANNEL_REGISTERED, ctx);
	}

    /* remove from netty-all 5.0.0.Alpha1
	@Override
	public void channelUnregistered(final ChannelHandlerContext ctx) throws Exception {
		super.channelUnregistered(ctx);
		
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("channelUnregistered: ch{}, uri:{}", ctx.channel(), uri);
		}
		
		eventReceiver.acceptEvent(NettyEvents.CHANNEL_UNREGISTERED, ctx);
	}
	*/

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("channelActive: ch({})", ctx.channel());
        }

		super.channelActive(ctx);
		this._receiver.acceptEvent(NettyEvents.CHANNEL_ACTIVE, ctx);
	}

	@Override
	public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("channelInactive: ch({})", ctx.channel());
        }
        
		super.channelInactive(ctx);
		this._receiver.acceptEvent(NettyEvents.CHANNEL_INACTIVE, ctx);
	}

	@Override
	public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("channelReadComplete: ch({})", ctx.channel());
        }
        
		super.channelReadComplete(ctx);
		this._receiver.acceptEvent(NettyEvents.CHANNEL_READCOMPLETE, ctx);
	}

	@Override
	public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt)
			throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("userEventTriggered: ch({}) with event:{}", ctx.channel(), evt);
        }
        
		super.userEventTriggered(ctx, evt);
		this._receiver.acceptEvent(NettyEvents.CHANNEL_USEREVENTTRIGGERED, ctx, evt);
	}

	@Override
	public void channelWritabilityChanged(final ChannelHandlerContext ctx)
			throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("channelWritabilityChanged: ch({}) writable:{}", 
                    ctx.channel(), ctx.channel().isWritable() );
        }
        
		super.channelWritabilityChanged(ctx);
		this._receiver.acceptEvent(NettyEvents.CHANNEL_WRITABILITYCHANGED, ctx);
	}
	
    @Override
    public void exceptionCaught(
            ChannelHandlerContext ctx, Throwable cause) throws Exception {
    	LOG.warn("exceptionCaught: ch(" + ctx.channel() + "), detail:", cause);
    	try {
    		this._receiver.acceptEvent(NettyEvents.CHANNEL_EXCEPTIONCAUGHT, ctx, cause);
    	}
    	finally {
    		ctx.close();
    	}
    }
	
	@Override
	protected void channelRead0/*messageReceived*/(
			final ChannelHandlerContext ctx,
			final I msg) throws Exception {
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("messageReceived: ch{}, msg:{}", ctx.channel(), msg );
		}
		
		this._receiver.acceptEvent(CHANNEL_MESSAGERECEIVED_EVENT, ctx, msg);
	}

	protected final EventReceiver _receiver;
}
