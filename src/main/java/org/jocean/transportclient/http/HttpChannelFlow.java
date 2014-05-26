/**
 * 
 */
package org.jocean.transportclient.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.event.api.internal.EventHandler;
import org.jocean.idiom.ArgsHandler;
import org.jocean.idiom.ArgsHandlerSource;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.transportclient.TransportEvents;
import org.jocean.transportclient.api.HttpClient;
import org.jocean.transportclient.api.HttpReactor;
import org.jocean.transportclient.http.Events.FlowEvents;
import org.jocean.transportclient.http.Events.HttpEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 * 
 */
class HttpChannelFlow extends AbstractFlow<HttpChannelFlow> 
    implements Comparable<HttpChannelFlow>, ArgsHandlerSource {
    
    interface Holder {
        public void addToIdleHttps(final URI domain, final HttpChannelFlow channelFlow);
        public void removeFromIdleHttps(final URI domain, final HttpChannelFlow channelFlow);

        public void addToBindedHttps(final HttpChannelFlow channelFlow);
        public void removeFromBindedHttps(final HttpChannelFlow channelFlow);
        
        public void addToInactiveHttps(final HttpChannelFlow channelFlow);
        public void removeFromInactiveHttps(final HttpChannelFlow channelFlow);
        
        public URI genDomainByURI(final URI uri);
        public Channel newChannel();
    }
    
    private static final Logger LOG = LoggerFactory
            .getLogger("http.HttpChannelFlow");

    HttpChannelFlow(final Holder holder, final BytesPool bytesPool) {
        this._holder = holder;
        this._bytesPool = bytesPool;
    }

    @Override
    public ArgsHandler getArgsHandler() {
        return ArgsHandler.Consts._REFCOUNTED_ARGS_GUARD;
    }
    
    final BizStep INACTIVE = new BizStep("http.INACTIVE")
            .handler(selfInvoker("inactiveOnAttaching"))
            .handler(selfInvoker("inactiveOnStartAttachFailed")).freeze();

    private final BizStep BINDED_CONNECTING = new BizStep(
            "http.BINDED_CONNECTING")
            .handler(selfInvoker("bindedConnectingOnAttaching"))
            .handler(selfInvoker("bindedConnectingOnAttachedFailed"))
            .handler(selfInvoker("bindedOnConnectOperationComplete"))
            .handler(selfInvoker("bindedConnectingOnActive"))
            .handler(selfInvoker("bindedConnectingOnDetach")).freeze();

    private final BizStep BINDED_ACTIVED = new BizStep("http.BINDED_ACTIVED")
            .handler(selfInvoker("bindedActivedOnAttaching"))
            .handler(selfInvoker("bindedActivedOnAttachedFailed"))
            .handler(selfInvoker("bindedOnInactive"))
            .handler(selfInvoker("bindedSendHttpRequest"))
            .handler(selfInvoker("bindedActivedOnDetach")).freeze();

    private final BizStep BINDED_TRANSACTING = new BizStep(
            "http.BINDED_TRANSACTING")
            .handler(selfInvoker("bindedTransactingOnAttaching"))
            .handler(selfInvoker("bindedOnInactive"))
            .handler(selfInvoker("bindedResponseReceived"))
            .handler(selfInvoker("bindedContentReceived"))
            .handler(selfInvoker("bindedLastContentReceived"))
            .handler(selfInvoker("bindedTransactingOnDetach")).freeze();

    private final BizStep IDLE_CONNECTING = new BizStep("http.IDLE_CONNECTING")
            .handler(selfInvoker("idleOnConnectOperationComplete"))
            .handler(selfInvoker("idleConnectingOnAttaching"))
            .handler(selfInvoker("idleConnectingOnActive"))
            .handler(selfInvoker("idleOnStartAttachFailed")).freeze();

    private final BizStep IDLE_ACTIVED = new BizStep("http.IDLE_ACTIVED")
            .handler(selfInvoker("idleActivedOnAttaching"))
            .handler(selfInvoker("idleActivedOnInactive"))
            .handler(selfInvoker("idleOnStartAttachFailed")).freeze();

    @OnEvent(event = FlowEvents.ATTACHING)
    private EventHandler inactiveOnAttaching(final HandleFlow handleFlow)
            throws Exception {
        this._holder.removeFromInactiveHttps(this);
        notifyHandleFlowAttached(handleFlow);
        createChannelAndConnectBy(handleFlow);
        this._holder.addToBindedHttps(this);

        return BINDED_CONNECTING;
    }

    @OnEvent(event = FlowEvents.START_ATTACH_FAILED)
    private EventHandler inactiveOnStartAttachFailed() {
        this._holder.addToInactiveHttps(this);
        return currentEventHandler();
    }

    @OnEvent(event = FlowEvents.ATTACHING)
    private EventHandler bindedConnectingOnAttaching(final HandleFlow handleFlow)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("channelFlow({})/{}/{} already binded handleFlow({}), but interrupt by high priority handleFlow({})",
                    this, currentEventHandler().getName(), currentEvent(),
                    this._bindedHandleFlow, handleFlow);
        }
        
        notifyHandleForChannelLost();
        notifyHandleFlowAttached(handleFlow);
        
        final URI toBindedDomain = this._holder.genDomainByURI(handleFlow.context().uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) handleFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedHandleFlow(handleFlow);
            this._holder.addToBindedHttps(this);
            return currentEventHandler();
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) handleFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            resetBindedHandleFlow();
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(handleFlow);
            this._holder.addToBindedHttps(this);
            return this.BINDED_CONNECTING;
        }
    }

    @OnEvent(event = FlowEvents.ATTACHED_FAILED)
    private EventHandler bindedConnectingOnAttachedFailed(
            final HandleFlow sourceHandleFlow) {
        if (!isCurrentHandleFlow(sourceHandleFlow)) {
            return currentEventHandler();
        }
        this._holder.removeFromBindedHttps(this);
        resetBindedHandleFlow();
        this._holder.addToIdleHttps(this._domain, this);
        return this.IDLE_CONNECTING;
    }

    @OnEvent(event = "operationComplete")
    private EventHandler bindedOnConnectOperationComplete(
            final ChannelFuture future) throws Exception {
        if (!isCurrentChannelResult(future)) {
            LOG.warn(
                    "bindedOnConnectOperationComplete: current uri:{} receive !NOT! current connect result for channel({}",
                    this._uri, future.channel());
            // just ignore
            return currentEventHandler();
        }
        if (!future.isSuccess()) {
            // future.isSuccess() will handle by
            // TransportEvents.CHANNEL_ACTIVE event
            // so just handle failed case
            LOG.warn("uri:" + this._uri + "'s channel(" + this._channel
                    + ") connect failed, detail:", future.cause());
            notifyHandleForChannelLost();
            this._holder.removeFromBindedHttps(this);
            resetBindedHandleFlow();
            this._holder.addToInactiveHttps(this);
            return this.INACTIVE;
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("cconnect to uri:{} succeed", this._uri);
            }
            return currentEventHandler();
        }
    }

    @OnEvent(event = TransportEvents.CHANNEL_ACTIVE)
    private EventHandler bindedConnectingOnActive(
            final ChannelHandlerContext ctx) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("channelFlow({})/{}/{} Actived by channel({})",
                    this, currentEventHandler().getName(), currentEvent(), ctx.channel());
        }
        notifyReactorHttpClientObtained();
        return this.BINDED_ACTIVED;
    }

    @OnEvent(event = "detach")
    private EventHandler bindedConnectingOnDetach(
            final HandleFlow sourceHandleFlow) {
        if (!isCurrentHandleFlow(sourceHandleFlow)) {
            return currentEventHandler();
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("HttpChannelFlow({}) has been detach.", this);
        }
        this._holder.removeFromBindedHttps(this);
        resetBindedHandleFlow();
        this._holder.addToIdleHttps(this._domain, this);
        return this.IDLE_CONNECTING;
    }

    @OnEvent(event = TransportEvents.CHANNEL_INACTIVE)
    private EventHandler bindedOnInactive(final ChannelHandlerContext ctx)
            throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("channel for {} closed.", this._uri);
        }
        notifyHandleForChannelLost();
        this._holder.removeFromBindedHttps(this);
        resetBindedHandleFlow();
        this._holder.addToInactiveHttps(this);
        return this.INACTIVE;
    }

    @OnEvent(event = FlowEvents.ATTACHING)
    private EventHandler bindedActivedOnAttaching(final HandleFlow handleFlow)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("channelFlow({})/{}/{} already binded handleFlow({}), but interrupt by high priority handleFlow({})",
                    this, currentEventHandler().getName(), currentEvent(),
                    this._bindedHandleFlow, handleFlow);
        }
        
        notifyHandleForChannelLost();
        notifyHandleFlowAttached(handleFlow);
        
        final URI toBindedDomain = this._holder.genDomainByURI(handleFlow.context().uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) handleFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedHandleFlow(handleFlow);
            notifyReactorHttpClientObtained();
            this._holder.addToBindedHttps(this);
            return currentEventHandler();
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) handleFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            resetBindedHandleFlow();
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(handleFlow);
            this._holder.addToBindedHttps(this);
            return this.BINDED_CONNECTING;
        }
    }
    
    @OnEvent(event = FlowEvents.ATTACHED_FAILED)
    private EventHandler bindedActivedOnAttachedFailed(
            final HandleFlow sourceHandleFlow) {
        if (!isCurrentHandleFlow(sourceHandleFlow)) {
            return currentEventHandler();
        }
        this._holder.removeFromBindedHttps(this);
        resetBindedHandleFlow();
        this._holder.addToIdleHttps(this._domain, this);
        return this.IDLE_ACTIVED;
    }

    @OnEvent(event = "detach")
    private EventHandler bindedActivedOnDetach(final HandleFlow sourceHandleFlow) {
        if (!isCurrentHandleFlow(sourceHandleFlow)) {
            return currentEventHandler();
        }
        this._holder.removeFromBindedHttps(this);
        resetBindedHandleFlow();
        this._holder.addToIdleHttps(this._domain, this);
        return this.IDLE_ACTIVED;
    }

    @OnEvent(event = "sendHttpRequest")
    private EventHandler bindedSendHttpRequest(final HttpReactor sourceReactor,
            final HttpRequest request) {
        if (!isCurrentHttpReactor(sourceReactor)) {
            return currentEventHandler();
        }
        request.headers().set(HttpHeaders.Names.CONNECTION,
                HttpHeaders.Values.KEEP_ALIVE);
        this._channel.writeAndFlush(request);
        if (LOG.isDebugEnabled()) {
            LOG.debug("({})/{}/{}: detail: {}", this, currentEventHandler()
                    .getName(), currentEvent(), request);
        }
        return this.BINDED_TRANSACTING;
    }

    @OnEvent(event = FlowEvents.ATTACHING)
    private EventHandler bindedTransactingOnAttaching(final HandleFlow handleFlow)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "channelFlow({})/{}/{} already binded handleFlow({}), but interrupt by high priority handleFlow({})",
                    this, currentEventHandler().getName(), currentEvent(),
                    this._bindedHandleFlow, handleFlow);
        }

        notifyHandleForChannelLost();

        resetBindedHandleFlow();
        notifyHandleFlowAttached(handleFlow);

        // close detach previous channel and re-try
        closeAndDetachCurrentChannel();
        createChannelAndConnectBy(handleFlow);
        this._holder.addToBindedHttps(this);

        return this.BINDED_CONNECTING;
    }
    
    @OnEvent(event = HttpEvents.HTTPRESPONSERECEIVED)
    private EventHandler bindedResponseReceived(
            final ChannelHandlerContext ctx, final HttpResponse response) {
        if (null != this._reactor) {
            try {
                this._reactor.onHttpResponseReceived(response);
            } catch (Exception e) {
                LOG.warn("exception when invoke onHttpResponseReceived", e);
            }
        } else {
            LOG.warn(
                    "uri:{} response received with internal error bcs non-reactor",
                    _uri);
        }

        if (!HttpUtils.isHttpResponseHasMoreContent(response)) {
            return this.BINDED_ACTIVED;
        } else {
            return currentEventHandler();
        }
    }

    @OnEvent(event = HttpEvents.HTTPCONTENTRECEIVED)
    private EventHandler bindedContentReceived(final ChannelHandlerContext ctx,
            final Blob blob) {
        if (null != this._reactor) {
            try {
                this._reactor.onHttpContentReceived(blob);
            } catch (Exception e) {
                LOG.warn("exception when invoke onHttpContentReceived", e);
            }
        } else {
            LOG.warn(
                    "uri:{} content received with internal error bcs non-reactor",
                    _uri);
        }

        return currentEventHandler();
    }

    @OnEvent(event = HttpEvents.LASTHTTPCONTENTRECEIVED)
    private EventHandler bindedLastContentReceived(
            final ChannelHandlerContext ctx, final Blob blob)
            throws Exception {
        if (null != this._reactor) {
            try {
                this._reactor.onLastHttpContentReceived(blob);
            } catch (Exception e) {
                LOG.warn("exception when invoke onLastHttpContentReceived", e);
            }
        } else {
            LOG.warn(
                    "uri:{} last content received with internal error bcs non-reactor",
                    _uri);
        }

        return this.BINDED_ACTIVED;
    }

    @OnEvent(event = "detach")
    private EventHandler bindedTransactingOnDetach(
            final HandleFlow sourceHandleFlow) throws Exception {
        if (!isCurrentHandleFlow(sourceHandleFlow)) {
            return currentEventHandler();
        }
        closeAndDetachCurrentChannel();
        this._holder.removeFromBindedHttps(this);
        resetBindedHandleFlow();
        this._holder.addToInactiveHttps(this);
        return this.INACTIVE;
    }

    @OnEvent(event = "operationComplete")
    private EventHandler idleOnConnectOperationComplete(
            final ChannelFuture future) {
        if (!isCurrentChannelResult(future)) {
            LOG.warn(
                    "idleOnConnectOperationComplete: domain:{} receive !NOT! current connect result for channel({}",
                    this._domain, future.channel());
            // just ignore
            return currentEventHandler();
        }
        if (!future.isSuccess()) {
            // future.isSuccess() will handle by
            // TransportEvents.CHANNEL_ACTIVE event
            // so just handle failed case
            LOG.warn("uri:" + this._uri + "'s IDLE channel(" + this._channel
                    + ") connect failed, detail:", future.cause());
            this._holder.removeFromIdleHttps(this._domain, this);
            this._holder.addToInactiveHttps(this);
            return this.INACTIVE;
        } else {
            return currentEventHandler();
        }
    }

    @OnEvent(event = FlowEvents.ATTACHING)
    private EventHandler idleConnectingOnAttaching(final HandleFlow handleFlow)
            throws Exception {
        this._holder.removeFromIdleHttps(this._domain, this);
        notifyHandleFlowAttached(handleFlow);
        final URI toBindedDomain = this._holder.genDomainByURI(handleFlow.context().uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) handleFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedHandleFlow(handleFlow);
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) handleFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(handleFlow);
        }
        this._holder.addToBindedHttps(this);
        return this.BINDED_CONNECTING;
    }

    @OnEvent(event = TransportEvents.CHANNEL_ACTIVE)
    private EventHandler idleConnectingOnActive(final ChannelHandlerContext ctx) {
        return this.IDLE_ACTIVED;
    }

    @OnEvent(event = FlowEvents.START_ATTACH_FAILED)
    private EventHandler idleOnStartAttachFailed() {
        this._holder.addToIdleHttps(this._domain, this);
        return currentEventHandler();
    }

    @OnEvent(event = FlowEvents.ATTACHING)
    private EventHandler idleActivedOnAttaching(final HandleFlow handleFlow)
            throws Exception {
        this._holder.removeFromIdleHttps(this._domain, this);
        notifyHandleFlowAttached(handleFlow);
        final URI toBindedDomain = this._holder.genDomainByURI(handleFlow.context().uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) handleFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedHandleFlow(handleFlow);
            notifyReactorHttpClientObtained();
            this._holder.addToBindedHttps(this);
            return this.BINDED_ACTIVED;
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) handleFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(handleFlow);
            this._holder.addToBindedHttps(this);
            return this.BINDED_CONNECTING;
        }
    }

    @OnEvent(event = TransportEvents.CHANNEL_INACTIVE)
    private EventHandler idleActivedOnInactive(final ChannelHandlerContext ctx) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("IDLE channelFlow({}) closed.", this);
        }
        this._holder.removeFromIdleHttps(this._domain, this);
        this._holder.addToInactiveHttps(this);
        return this.INACTIVE;
    }

    @Override
    public EventReceiver selfEventReceiver() {
        return super.selfEventReceiver();
    }

    @SuppressWarnings("unchecked")
    private GenericFutureListener<ChannelFuture> genConnectListener() {
        return this.queryInterfaceInstance(GenericFutureListener.class);
    }

    private boolean isCurrentChannelResult(final ChannelFuture future) {
        return this._channel == future.channel();
    }

    private boolean isCurrentHandleFlow(final HandleFlow sourceHandleFlow) {
        final boolean ret = (this._bindedHandleFlow == sourceHandleFlow);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "HttpChannelFlow({})/{}/{}: source flow({}) is !NOT! current binded HandleFlow({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        sourceHandleFlow, this._bindedHandleFlow);
            }
        }
        return ret;
    }

    private boolean isCurrentHttpReactor(final HttpReactor sourceReactor) {
        final boolean ret = (this._reactor == sourceReactor);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "HttpChannelFlow({})/{}/{}: source reactor({}) is !NOT! current binded HttpReactor({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        sourceReactor, this._reactor);
            }
        }
        return ret;
    }

    /**
     * @param domain
     * @return
     */
    private boolean isCurrentDomainEquals(final URI domain) {
        return this._domain.equals(domain);
    }

    /**
     * @param handleFlow
     */
    private void createChannelAndConnectBy(final HandleFlow handleFlow) {
        updateBindedHandleFlow(handleFlow);

        this._channel = this._holder.newChannel();
        this._channelDetacher = HttpUtils.addHttpCodecToChannel(
                this._channel,
                this._domain, 
                this._bytesPool,
                this.selfEventReceiver());

        this._connectFuture = this._channel.connect(new InetSocketAddress(
                this._domain.getHost(), this._domain.getPort()));
        this._connectFuture.addListener(genConnectListener());
    }

    /**
 * 
 */
    private void updateBindedHandleFlow(final HandleFlow handleFlow) {
        this._uri = handleFlow.context().uri();
        this._domain = this._holder.genDomainByURI(this._uri);
        this._ctx = new HandleContextImpl<HttpChannelFlow>(handleFlow.context(),
                this);
        this._bindedHandleFlow = handleFlow;
        this._handleReceiver = handleFlow.selfEventReceiver();
        this._reactor = handleFlow._reactor;
    }

    private void resetBindedHandleFlow() {
        this._ctx = null;
        this._bindedHandleFlow = null;
        this._handleReceiver = null;
        this._reactor = null;
    }

    /**
     * @throws Exception
     */
    private void closeAndDetachCurrentChannel() throws Exception {
        if (null != this._connectFuture && !this._connectFuture.isDone()) {
            this._connectFuture.cancel(false);
        }
        if (null != this._channel) {
            this._channel.close();
        }
        if (null != this._channelDetacher) {
            this._channelDetacher.detach();
        }
        this._connectFuture = null;
        this._channel = null;
        this._channelDetacher = null;
    }

    /**
     * @throws Exception
     */
    private void notifyHandleForChannelLost() throws Exception {
        if (null != this._handleReceiver) {
            this._handleReceiver.acceptEvent(FlowEvents.CHANNELLOST);
        }
    }

    private void notifyHandleFlowAttached(final HandleFlow handleFlow)
            throws Exception {
        handleFlow.selfEventReceiver().acceptEvent(
                new AbstractUnhandleAware(FlowEvents.ATTACHED) {
                    @Override
                    public void onEventUnhandle(final String event,
                            final Object... args) throws Exception {
                        selfEventReceiver().acceptEvent(FlowEvents.ATTACHED_FAILED,
                                handleFlow);
                    }
                }, this);
    }

    private void notifyReactorHttpClientObtained() throws Exception {
        if (null != this._handleReceiver) {
            final HttpReactor sourceReactor = this._reactor;
            this._handleReceiver.acceptEvent("onHttpClientObtained",
                    new HttpClient() {
                        @Override
                        public void sendHttpRequest(final HttpRequest request)
                                throws Exception {
                            selfEventReceiver().acceptEvent(
                                    "sendHttpRequest", sourceReactor, request);
                        }
                    });
        }
    }

    public URI bindedDomain() {
        return this._domain;
    }
    
    public HandleContextImpl<HttpChannelFlow> bindedContext() {
        return this._ctx;
    }
    
    @Override
    public String toString() {
        return "ChannelFlow [id=" + _id + ", state("
                + currentEventHandler().getName() + "), channel=" + _channel
                + ", bindedCtx=" + _ctx + ", domain=" + _domain
                + ", channelDetacher("
                + (null != _channelDetacher ? "not null" : "null")
                + ")/connectFuture("
                + (null != _connectFuture ? "not null" : "null") + ")/reactor("
                + (null != _reactor ? "not null" : "null")
                + ")/handleReceiver("
                + (null != _handleReceiver ? "not null" : "null")
                + ")/bindedHandleFlow("
                + (null != _bindedHandleFlow ? "not null" : "null") + ")]";
    }

    private final Holder _holder;
    private final BytesPool _bytesPool;
    private Channel _channel;
    private Detachable _channelDetacher;
    private ChannelFuture _connectFuture;
    private URI _uri;
    private URI _domain;
    private HttpReactor _reactor = null;
    private EventReceiver _handleReceiver = null;
    private HandleFlow _bindedHandleFlow = null;
    private volatile HandleContextImpl<HttpChannelFlow> _ctx = null;

    private final int _id = _FLOW_IDSRC.getAndIncrement();

    private static final AtomicInteger _FLOW_IDSRC = new AtomicInteger(0);
    
    @Override
    public int compareTo(final HttpChannelFlow o) {
        return this._id - o._id;
    }
}
