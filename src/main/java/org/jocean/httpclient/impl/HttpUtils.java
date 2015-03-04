/**
 * 
 */
package org.jocean.httpclient.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import javax.net.ssl.SSLEngine;

import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.PairedGuardEventable;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.block.PooledBytesOutputStream;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.netty.EventReceiverInboundHandler;
import org.jocean.netty.NettyEvents;
import org.jocean.netty.NettyUtils;
import org.jocean.ssl.FixNeverReachFINISHEDStateSSLEngine;
import org.jocean.ssl.SecureChatSslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class HttpUtils {
	private static final Logger LOG =
			LoggerFactory.getLogger(HttpUtils.class);
	
    public static boolean isHttpClientError(final HttpResponse response) {
        return response.getStatus().code() >= 400 
            && response.getStatus().code() < 500;
    }

    public static boolean isHttpServerError(final HttpResponse response) {
        return response.getStatus().code() >= 500 
            && response.getStatus().code() < 600;
    }
    
	// return downloadable content's total length 
	// (NOTE: 在断点续传的情况下 内容总长度不是本次传输长度而是包含已下载部分的内容总长度 )
	// 返回值 可以为null
	public static String getContentTotalLengthFromResponse(final HttpResponse response) {
        final String contentRange = response.headers().get(HttpHeaders.Names.CONTENT_RANGE);
        if ( null != contentRange ) {
            return getPartialTotalFromContentRange(contentRange);
        }
        else {
            return response.headers().get(HttpHeaders.Names.CONTENT_LENGTH);
        }
	}
	
    public static long getContentTotalLengthFromResponseAsLong(final HttpResponse response, final long defaultValue) {
        final String lengthAsString = getContentTotalLengthFromResponse(response);
        try {
            return null != lengthAsString ? Long.parseLong(lengthAsString) : defaultValue;
        }
        catch (Throwable e) {
            LOG.warn("exception when Long.parseLong for {}, detail: {}", 
                    lengthAsString, ExceptionUtils.exception2detail(e));
            return defaultValue;
        }
    }
    
	//	return null means no Partial Begin
	public static String getPartialBeginFromContentRange(final String contentRange) {
		if ( null == contentRange ) {
			return	null;
		}
		//	Content-Range: bytes (unit first byte pos) - [last byte pos]/[entity legth] 
		//	eg: Content-Range: bytes 0-800/801 //801:文件总大小
		final int bytesStart = contentRange.indexOf("bytes ");
		final String bytesRange = ( -1 != bytesStart ? contentRange.substring(bytesStart + 6) : contentRange);
		final int dashStart = bytesRange.indexOf('-');
		return ( -1 != dashStart ? bytesRange.substring(0, dashStart) : null);
	}
	
	//	return null means no Partial Total
	public static String getPartialTotalFromContentRange(final String contentRange) {
		if ( null == contentRange ) {
			return	null;
		}
		//	Content-Range: bytes (unit first byte pos) - [last byte pos]/[entity legth] 
		//	eg: Content-Range: bytes 0-800/801 //801:文件总大小
		final int totalStart = contentRange.indexOf('/');
		return ( -1 != totalStart ? contentRange.substring(totalStart + 1) : null);
	}
	
	public static boolean isHttpResponseHasMoreContent(final HttpResponse response) {
//		HTTP/1.1 默认的连接方式是长连接，不能通过简单的TCP连接关闭判断HttpMessage的结束。
//		以下是几种判断HttpMessage结束的方式：
//		 
//		1.      HTTP协议约定status code 为1xx，204，304的应答消息不能包含消息体（Message Body）, 直接忽略掉消息实体内容。
//		        [适用于应答消息]
//		        Http Message =Http Header
//		2.      如果请求消息的Method为HEAD，则直接忽略其消息体。[适用于请求消息]
//		         Http Message =Http Header
//		3.      如果Http消息头部有“Transfer-Encoding:chunked”，则通过chunk size判断长度。
//		4.      如果Http消息头部有Content-Length且没有Transfer-Encoding（如果同时有Content-Length和Transfer-Encoding,则忽略Content-Length），
//		         则通过Content-Length判断消息体长度。
//		5.      如果采用短连接（Http Message头部Connection:close），则直接可以通过服务器关闭连接来确定消息的传输长度。
//		         [适用于应答消息，Http请求消息不能以这种方式确定长度]
//		6.      还可以通过接收消息超时判断，但是不可靠。Python Proxy实现的http代理服务器用到了超时机制，源码地址见References[7]，仅100多行。		
		final HttpResponseStatus status = response.getStatus();
		if ( status.code() >= 100 && status.code() < 200 ) {
			return false;
		}
		if ( status.code() == 204 || status.code() == 304 ) {
			return false;
		}
		if (HttpHeaders.isTransferEncodingChunked(response)) {
			return true;
		}
		if (HttpHeaders.isContentLengthSet(response)) {
			return (HttpHeaders.getContentLength(response) > 0);
		}
		//    add 2015-01-29 to fix bug for Connection: close setted response
		if (!HttpHeaders.isKeepAlive(response) ) {
		    //    如果采用短连接（Http Message头部Connection:close），则直接可以通过服务器关闭连接来确定消息的传输长度。
		    //    因此此时默认认为 response 还有更多的Http Content
		    return true;
		}
		return false;
	}
	
    final static class HttpEvents {
        //  HTTP events
        //  params: ChannelHandlerContext ctx, HttpResponse response
        static final String HTTPRESPONSERECEIVED    = "_httpResponseReceived";

        //  params: ChannelHandlerContext ctx, HttpContent content
        static final String HTTPCONTENTRECEIVED     = "_httpContentReceived";

        //  params: ChannelHandlerContext ctx, LastHttpContent lastContent
        static final String LASTHTTPCONTENTRECEIVED= "_lastHttpContentReceived";
    }
    
	private static final class HttpHandler extends EventReceiverInboundHandler<HttpObject> {
	    
        private static final PairedGuardEventable HTTPRESPONSERECEIVED_EVENT = 
                new PairedGuardEventable(NettyUtils._NETTY_REFCOUNTED_GUARD, HttpEvents.HTTPRESPONSERECEIVED);
        
		private static final PairedGuardEventable HTTPCONTENTRECEIVED_EVENT = 
                new PairedGuardEventable(NettyUtils._NETTY_REFCOUNTED_GUARD, HttpEvents.HTTPCONTENTRECEIVED);

        private static final PairedGuardEventable LASTHTTPCONTENTRECEIVED_EVENT = 
                new PairedGuardEventable(NettyUtils._NETTY_REFCOUNTED_GUARD, HttpEvents.LASTHTTPCONTENTRECEIVED);
		
        private final boolean _sslEnabled;
		
		public HttpHandler(final EventReceiver receiver, final boolean sslEnabled) {
			super(receiver);
			this._sslEnabled = sslEnabled;
		}
		
		@Override
		public void channelActive(final ChannelHandlerContext ctx) throws Exception {
	        if ( LOG.isDebugEnabled() ) {
	            LOG.debug("channelActive: ch({})", ctx.channel());
	        }
	        
	        ctx.fireChannelActive();
			
			if ( !this._sslEnabled ) {
				this._receiver.acceptEvent(NettyEvents.CHANNEL_ACTIVE, ctx);
			}
		}
		
		@Override
		public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt)
				throws Exception {
			if ( LOG.isDebugEnabled() ) {
				LOG.debug("userEventTriggered: ch({}) with event:{}", ctx.channel(), evt);
			}
			
            ctx.fireUserEventTriggered(evt);
			
			if ( this._sslEnabled && (evt instanceof SslHandshakeCompletionEvent)) {
				if ( ((SslHandshakeCompletionEvent)evt).isSuccess() ) {
    				this._receiver.acceptEvent(NettyEvents.CHANNEL_ACTIVE, ctx);
				}
			}
			else {
				this._receiver.acceptEvent(NettyEvents.CHANNEL_USEREVENTTRIGGERED, ctx, evt);
			}
		}
		
		@Override
		protected void channelRead0/*messageReceived*/(final ChannelHandlerContext ctx,
				final HttpObject msg) throws Exception {
			super.channelRead0(ctx, msg);
			
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("channelRead0: ch({}) with httpObj:{}", ctx.channel(), msg);
            }
            
	        if (msg instanceof HttpResponse) {
    			this._receiver.acceptEvent(HTTPRESPONSERECEIVED_EVENT, ctx, msg);
	        }
	        if (msg instanceof HttpContent) {
//                final Blob blob = httpContent2Blob((HttpContent)msg);
//                try {
                if (msg instanceof LastHttpContent) {
                    this._receiver.acceptEvent(LASTHTTPCONTENTRECEIVED_EVENT, ctx, msg);
                } else {
                    this._receiver.acceptEvent(HTTPCONTENTRECEIVED_EVENT, ctx, msg);
                }
//                }
//                finally {
//                    if (null!= blob) {
//                        blob.release();
//                    }
//                }
	        }
		}

	}
	
	static boolean ENABLE_HTTP_LOG = false;
	
	static public boolean enableHttpTransportLog(final boolean enabled) {
	    ENABLE_HTTP_LOG = enabled;
	    return enabled;
	}
	
	//	change to package static method
	static Detachable addHttpCodecToChannel(
			final Channel channel, 
			final URI uri, 
			final EventReceiver eventReceiver) {
        // Create a default pipeline implementation.
        final ChannelPipeline p = channel.pipeline();

        if ( ENABLE_HTTP_LOG ) {
        	p.addLast("log", new LoggingHandler(LogLevel.TRACE));
        }
        
        // Enable HTTPS if necessary.
        final String scheme = uri.getScheme();
        final boolean sslEnabled = "https".equalsIgnoreCase(scheme);
        if ( sslEnabled ) {
            final SSLEngine engine =
                SecureChatSslContextFactory.getClientContext().createSSLEngine();
            engine.setUseClientMode(true);

            p.addLast("ssl", new SslHandler(
            		FixNeverReachFINISHEDStateSSLEngine.fixAndroidBug( engine ), false));
        }

        p.addLast("codec", new HttpClientCodec());

        // Remove the following line if you don't want automatic content decompression.
        p.addLast("inflater", new HttpContentDecompressor());

        // Uncomment the following line if you don't want to handle HttpChunks.
        //p.addLast("aggregator", new HttpObjectAggregator(1048576));

        if ( null != eventReceiver ) {
            final HttpHandler handler = new HttpHandler(eventReceiver, sslEnabled);
            
        	p.addLast("handler", handler);
        	
        	return new Detachable() {
                @Override
                public void detach() throws Exception {
                    p.remove(handler);
                }};
        }
        else {
            return new Detachable() {
                @Override
                public void detach() throws Exception {
                }};
        }
	}

    public static Blob httpContent2Blob(
            final BytesPool bytesPool,
            final HttpContent content) {
        final PooledBytesOutputStream os = new PooledBytesOutputStream(
                bytesPool);
        if (byteBuf2OutputStream(content.content(), os) > 0) {
            return os.drainToBlob();
        } else {
            return null;
        }
    }

    /**
     * @param buf
     * @param os
     */
    public static long byteBuf2OutputStream(
            final ByteBuf buf,
            final PooledBytesOutputStream os) {
        final InputStream is = new ByteBufInputStream(buf);
        try {
            return BlockUtils.inputStream2OutputStream(is, os);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                // just ignore
            }
        }
    }
    
    /**
     * @param contentBlob
     * @return
     * @throws IOException
     */
    public static ByteBuf blob2ByteBuf(final Blob contentBlob) throws IOException {
        byte[] bytes = new byte[0];
        if ( null != contentBlob ) {
            final InputStream is = contentBlob.genInputStream();
            bytes = new byte[is.available()];
            is.read(bytes);
            is.close();
        }
        return Unpooled.wrappedBuffer(bytes);
    }
}
