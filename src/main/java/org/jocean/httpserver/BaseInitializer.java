package org.jocean.httpserver;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseInitializer<C extends Channel> extends ChannelInitializer<C> {
    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BaseInitializer.class);
    private static final LoggingHandler LOGGING_HANDLER = new LoggingHandler();

    private boolean logByteStream;
    private boolean logMessage = true;
    private int idleTimeSeconds = 0; // in seconds


    @Override
    public void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        if (logByteStream) {
            LOG.debug("ByteLogger enabled");
            pipeline.addLast("byteLogger", LOGGING_HANDLER);
        }
        if (idleTimeSeconds > 0) {
            pipeline.addLast("idleHandler", new CloseOnIdleHandler(0, 0, idleTimeSeconds, TimeUnit.SECONDS));
        }
        addCodecHandler(pipeline);
        if (logMessage) {
            LOG.debug("MessageLogger enabled");
            pipeline.addLast("messageLogger", LOGGING_HANDLER);
        }
        addBusinessHandler(pipeline);
    }

    /**
     * 增加编解码handler
     *
     * @param pipeline
     * @throws Exception
     */
    protected abstract void addCodecHandler(ChannelPipeline pipeline) throws Exception;

    /**
     * 增加负责处理具体业务逻辑的handler
     *
     * @param pipeline
     * @throws Exception
     */
    protected abstract void addBusinessHandler(ChannelPipeline pipeline) throws Exception;

    public boolean isLogByteStream() {
        return logByteStream;
    }

    /**
     * 是否提供记录二进制日志功能
     *
     * @param logByteStream
     */
    public void setLogByteStream(boolean logByteStream) {
        this.logByteStream = logByteStream;
    }

    public boolean isLogMessage() {
        return logMessage;
    }

    /**
     * 是否提供记录解码后的数据包日志功能
     *
     * @param logMessage
     */
    public void setLogMessage(boolean logMessage) {
        this.logMessage = logMessage;
    }

    public int getIdleTimeSeconds() {
        return idleTimeSeconds;
    }

    /**
     * 最大连接空闲时间，如果设置的值大于0，则空闲该时间后将关闭连接
     *
     * @param idleTimeSeconds 单位 秒
     */
    public void setIdleTimeSeconds(int idleTimeSeconds) {
        this.idleTimeSeconds = idleTimeSeconds;
    }
    
    
    private class CloseOnIdleHandler extends IdleStateHandler {

        public CloseOnIdleHandler(int readerIdleTimeSeconds, int writerIdleTimeSeconds, int allIdleTimeSeconds) {
            super(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds);
        }

        public CloseOnIdleHandler(long readerIdleTime, long writerIdleTime, long allIdleTime, TimeUnit unit) {
            super(readerIdleTime, writerIdleTime, allIdleTime, unit);
        }

        @Override
        protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
            if (LOG.isInfoEnabled()) {
                LOG.info("channelIdle: " + evt.state().name() + " , close channel[" + ctx.channel().remoteAddress() + "]");
            }
            ctx.channel().close();
        }
    }}
