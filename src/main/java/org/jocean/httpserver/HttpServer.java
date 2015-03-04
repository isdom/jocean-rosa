/**
 * 
 */
package org.jocean.httpserver;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
//import io.netty.handler.traffic.TrafficCounterExt;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import org.jocean.httpserver.ServerAgent.ServerTask;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class HttpServer {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(HttpServer.class);

    public static final AttributeKey<ServerTask> TASK = AttributeKey.valueOf("TASK");
    
    private static final int MAX_RETRY = 20;
    private static final long RETRY_TIMEOUT = 30 * 1000; // 30s
    
    private Channel _acceptorChannel;
    
    private int _acceptPort = 65000;
    private String _acceptIp = "0.0.0.0";
    
    private final EventLoopGroup _acceptorGroup = new NioEventLoopGroup();
    private final EventLoopGroup _clientGroup = new NioEventLoopGroup();
    
//    private TrafficCounterExt _trafficCounterExt;
    private boolean _logByteStream;
    // 为了避免建立了过多的闲置连接 闲置180秒的连接主动关闭
    private int 	_idleTimeSeconds = 180;
    private boolean _isCompressContent;
    private boolean _enableSSL;
    
	private ServerAgent  _serverAgent;
    
    //响应检查服务是否活着的请求
    private String _checkAlivePath = null;
    private final WorkHandler _workHandler = new WorkHandler();
    
    @ChannelHandler.Sharable
    private class WorkHandler extends ChannelInboundHandlerAdapter{
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOG.info("exceptionCaught {}, detail:{}", 
                    ctx.channel(), ExceptionUtils.exception2detail(cause));
            ctx.close();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }
        
        private ServerTask getTaskOf(final ChannelHandlerContext ctx) {
            return ctx.attr(TASK).get();
        }
        
        private void setTaskOf(final ChannelHandlerContext ctx, final ServerTask task) {
            ctx.attr(TASK).set(task);
        }
        
        /**
         * @param ctx
         * @throws Exception
         */
        private void detachCurrentTaskOf(ChannelHandlerContext ctx)
                throws Exception {
            final ServerTask task = getTaskOf(ctx);
            if ( null != task ) {
                task.detach();
                ctx.attr(TASK).remove();
            }
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            try {
                if (msg instanceof HttpRequest) {
                    final HttpRequest request = (HttpRequest)msg;
                    
                    if ( LOG.isDebugEnabled()) {
                        LOG.debug("messageReceived:{} default http request\n[{}]",ctx.channel(),request);
                    }
                    
                    if ( ifCheckAliveAndResponse(ctx, request) ) {
                        return;
                    }
                    
                    detachCurrentTaskOf(ctx);
                    
                    final ServerTask newTask = _serverAgent.createServerTask(ctx, request);
                    setTaskOf(ctx, newTask);
                }
                else if (msg instanceof HttpContent) {
                    final ServerTask task = getTaskOf(ctx);
                    if ( null != task ) {
                        task.onHttpContent((HttpContent)msg);
                    }
                    else {
                        LOG.warn("NO WORK TASK, messageReceived:{} unhandled msg [{}]",ctx.channel(),msg);
                    }
                }
                else {
                    LOG.warn("messageReceived:{} unhandled msg [{}]",ctx.channel(),msg);
                    return;
                }
            }
            finally {
                ReferenceCountUtil.release(msg);
            }
        }
        
        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            detachCurrentTaskOf(ctx);
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        }
    }
    
    /**
     * @param ctx
     * @param request
     */
    private boolean ifCheckAliveAndResponse(
            final ChannelHandlerContext ctx,
            final HttpRequest request) {
        if ( null != this._checkAlivePath 
                && this._checkAlivePath.equalsIgnoreCase(request.getUri())) {
            // deal with check alive
            final HttpResponse response = new DefaultFullHttpResponse(
                    request.getProtocolVersion(), HttpResponseStatus.OK);
            HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
            final ChannelFuture future = ctx.writeAndFlush(response);
            if ( !HttpHeaders.isKeepAlive( request ) ) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            return true;
        }
        else {
            return false;
        }
    }
    
    public void start() throws Exception {
    	final ServerBootstrap bootstrap = 
    			new ServerBootstrap().group(this._acceptorGroup,this._clientGroup)
                .channel(NioServerSocketChannel.class)
                .localAddress(this._acceptIp,this._acceptPort)
                /*
                 * SO_BACKLOG
                 * Creates a server socket and binds it to the specified local port number, with the specified backlog.
                 * The maximum queue length for incoming connection indications (a request to connect) is set to the backlog parameter. 
                 * If a connection indication arrives when the queue is full, the connection is refused. 
                 */
                .option(ChannelOption.SO_BACKLOG, 10240)
                /*
                 * SO_REUSEADDR
                 * Allows socket to bind to an address and port already in use. The SO_EXCLUSIVEADDRUSE option can prevent this. 
                 * Also, if two sockets are bound to the same port the behavior is undefined as to which port will receive packets.
                 */
                .option(ChannelOption.SO_REUSEADDR, true)
                /*
                 * SO_RCVBUF
                 * The total per-socket buffer space reserved for receives. 
                 * This is unrelated to SO_MAX_MSG_SIZE and does not necessarily correspond to the size of the TCP receive window. 
                 */
                //.option(ChannelOption.SO_RCVBUF, 8 * 1024)
                /*
                 * SO_SNDBUF
                 * The total per-socket buffer space reserved for sends. 
                 * This is unrelated to SO_MAX_MSG_SIZE and does not necessarily correspond to the size of a TCP send window.
                 */
                //.option(ChannelOption.SO_SNDBUF, 8 * 1024)
                /*
                 * SO_KEEPALIVE
                 * Enables keep-alive for a socket connection. 
                 * Valid only for protocols that support the notion of keep-alive (connection-oriented protocols). 
                 * For TCP, the default keep-alive timeout is 2 hours and the keep-alive interval is 1 second. 
                 * The default number of keep-alive probes varies based on the version of Windows. 
                 */
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                /*
                 * TCP_NODELAY
                 * Enables or disables the Nagle algorithm for TCP sockets. This option is disabled (set to FALSE) by default.
                 */
                .childOption(ChannelOption.TCP_NODELAY, true)
                /*
                 * SO_LINGER
                 * Indicates the state of the linger structure associated with a socket. 
                 * If the l_onoff member of the linger structure is nonzero, 
                 * a socket remains open for a specified amount of time 
                 * after a closesocket function call to enable queued data to be sent. 
                 * The amount of time, in seconds, to remain open 
                 * is specified in the l_linger member of the linger structure. 
                 * This option is only valid for reliable, connection-oriented protocols.
                 */
                .childOption(ChannelOption.SO_LINGER, -1);
        
//        if ( null != this._trafficCounterExt) {
//            this._baseInitializer.setTrafficCounter(this._trafficCounterExt);
//        }
        
		final SslContext sslCtx;
        if (this._enableSSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
        } else {
            sslCtx = null;
        }
        final BaseInitializer<NioServerSocketChannel> initializer = 
        		new BaseInitializer<NioServerSocketChannel>(){
            @Override
            protected void addCodecHandler(final ChannelPipeline pipeline) throws Exception {
            	if (sslCtx != null) {
            		pipeline.addLast(sslCtx.newHandler(pipeline.channel().alloc()));
                }
            	
//                final SSLEngine engine = J2SESslContextFactory.getServerContext().createSSLEngine();
//                engine.setUseClientMode(false);
//                pipeline.addLast("ssl", new SslHandler(engine));
                
                //IN decoder
                pipeline.addLast("decoder",new HttpRequestDecoder());
                //OUT 统计数据流大小 这个handler需要放在HttpResponseToByteEncoder前面处理
//                pipeline.addLast("statistics",new StatisticsResponseHandler()); 
                //OUT encoder
                pipeline.addLast("encoder",new HttpResponseEncoder());
                
                //IN/OUT 支持压缩
                if ( _isCompressContent ) {
                    pipeline.addLast("deflater", new HttpContentCompressor());
                }
            }
            
            @Override
            protected void addBusinessHandler(final ChannelPipeline pipeline) throws Exception {
                pipeline.addLast("biz-handler", _workHandler);
            }
        };
        
        initializer.setLogByteStream(this._logByteStream);
        initializer.setIdleTimeSeconds(this._idleTimeSeconds);
        // 因为响应可能不是message，logMessage无法处理
        initializer.setLogMessage(false);
        
        bootstrap.childHandler(initializer);
        
        int retryCount = 0;
        boolean binded = false;

        do {
            try {
                this._acceptorChannel = bootstrap.bind().channel();
                binded = true;
            } catch (final ChannelException e) {
                LOG.warn("start failed : {}, and retry...", e);

                //  对绑定异常再次进行尝试
                retryCount++;
                if (retryCount >= MAX_RETRY) {
                    //  超过最大尝试次数
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_TIMEOUT);
                } catch (InterruptedException ignored) {
                }
            }
        } while (!binded);
        
    }

    public void stop() {
        if (null != this._acceptorChannel) {
            this._acceptorChannel.disconnect();
            this._acceptorChannel = null;
        }
        this._acceptorGroup.shutdownGracefully();
        this._clientGroup.shutdownGracefully();
    }
    
    public void setAcceptPort(int acceptPort) {
        this._acceptPort = acceptPort;
    }
 

    public void setAcceptIp(String acceptIp) {
        this._acceptIp = acceptIp;
    }

//    public int getChannelCount() {
//        return channelCount.intValue();
//    }

    public void setLogByteStream(final boolean logByteStream) {
        this._logByteStream = logByteStream;
    }

    public void setIdleTimeSeconds(final int idleTimeSeconds) {
        this._idleTimeSeconds = idleTimeSeconds;
    }
 
//    public void setPacketsFrequency(int packetsFrequency) {
//        this.packetsFrequency = packetsFrequency;
//    }

//    public void setTrafficCounterExt(TrafficCounterExt trafficCounterExt) {
//        this._trafficCounterExt = trafficCounterExt;
//    }
    
    public void setServerAgent(final ServerAgent serverAgent) {
        this._serverAgent = serverAgent;
    }

    public String getCheckAlivePath() {
        return this._checkAlivePath;
    }

    public void setCheckAlivePath(final String checkAlivePath) {
        this._checkAlivePath = checkAlivePath;
    }
    
    public boolean isCompressContent() {
        return this._isCompressContent;
    }

    public void setCompressContent(final boolean isCompressContent) {
        this._isCompressContent = isCompressContent;
    }
    
    public boolean isEnableSSL() {
		return this._enableSSL;
	}

	public void setEnableSSL(final boolean enableSSL) {
		this._enableSSL = enableSSL;
	}

}
