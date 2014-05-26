/**
 * 
 */
package org.jocean.transportclient.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
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
import org.jocean.idiom.Detachable;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.block.PooledBytesOutputStream;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.ssl.FixNeverReachFINISHEDStateSSLEngine;
import org.jocean.ssl.SecureChatSslContextFactory;
import org.jocean.transportclient.EventReceiverInboundHandler;
import org.jocean.transportclient.TransportEvents;
import org.jocean.transportclient.http.Events.HttpEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class HttpUtils {
	private static final Logger LOG =
			LoggerFactory.getLogger("transportclient.HttpUtils");
	
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
		if ( HttpHeaders.isTransferEncodingChunked(response) ) {
			return true;
		}
		if ( HttpHeaders.isContentLengthSet(response)) {
			return (HttpHeaders.getContentLength(response) > 0);
		}
		return false;
	}
	
	private static final class HttpHandler extends EventReceiverInboundHandler<HttpObject> {

		private final boolean _sslEnabled;
		private final BytesPool _bytesPool;
		
		public HttpHandler(final BytesPool bytesPool, final EventReceiver receiver, final boolean sslEnabled) {
			super(receiver);
			this._sslEnabled = sslEnabled;
			this._bytesPool = bytesPool;
		}
		
		@Override
		public void channelActive(final ChannelHandlerContext ctx) throws Exception {
	        if ( LOG.isDebugEnabled() ) {
	            LOG.debug("channelActive: ch({})", ctx.channel());
	        }
	        
	        ctx.fireChannelActive();
			
			if ( !this._sslEnabled ) {
				this._receiver.acceptEvent(TransportEvents.CHANNEL_ACTIVE, ctx);
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
    				this._receiver.acceptEvent(TransportEvents.CHANNEL_ACTIVE, ctx);
				}
			}
			else {
				this._receiver.acceptEvent(TransportEvents.CHANNEL_USEREVENTTRIGGERED, ctx, evt);
			}
		}
		
		@Override
		protected void channelRead0/*messageReceived*/(final ChannelHandlerContext ctx,
				final HttpObject msg) throws Exception {
			super.channelRead0(ctx, msg);
			
	        if (msg instanceof HttpResponse) {
    			this._receiver.acceptEvent(HttpEvents.HTTPRESPONSERECEIVED, ctx, (HttpResponse) msg);
	        }
	        if (msg instanceof HttpContent) {
                final Blob blob = httpContent2Blob((HttpContent)msg);
                try {
                    if (msg instanceof LastHttpContent) {
                        this._receiver.acceptEvent(HttpEvents.LASTHTTPCONTENTRECEIVED, ctx, blob);
                    }
                    else {
                        this._receiver.acceptEvent(HttpEvents.HTTPCONTENTRECEIVED, ctx, blob);
                    }
                }
                finally {
                    if (null!= blob) {
                        blob.release();
                    }
                }
	        }
		}

        private Blob httpContent2Blob(final HttpContent content) throws Exception {
            final PooledBytesOutputStream os = new PooledBytesOutputStream(this._bytesPool);
            if ( byteBuf2OutputStream(content.content(), os) > 0 ) {
                return os.drainToBlob();
            }
            else {
                return null;
            }
        }
	}
	
    /**
     * @param buf
     * @param os
     */
    private static long byteBuf2OutputStream(final ByteBuf buf, final PooledBytesOutputStream os) {
        final InputStream is = new ByteBufInputStream(buf);
        try {
            return BlockUtils.inputStream2OutputStream(is, os);
        }
        finally {
            try {
                is.close();
            } catch (IOException e) {
                // just ignore
            }
        }
    }
    
	static boolean ENABLE_HTTP_LOG = false;
	
	static public void enableHttpTransportLog(final boolean enabled) {
	    ENABLE_HTTP_LOG = enabled;
	}
	
	//	change to package static method
	static Detachable addHttpCodecToChannel(
			final Channel channel, 
			final URI uri, 
			final BytesPool bytesPool,
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
            final HttpHandler handler = new HttpHandler(bytesPool, eventReceiver, sslEnabled);
            
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

}
