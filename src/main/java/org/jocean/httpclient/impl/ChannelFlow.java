/**
 * 
 */
package org.jocean.httpclient.impl;

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
import org.jocean.httpclient.api.Guide.Requirement;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.httpclient.impl.HttpUtils.HttpEvents;
import org.jocean.idiom.ArgsHandler;
import org.jocean.idiom.ArgsHandlerSource;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ValidationId;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.netty.NettyEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 * 
 */
class ChannelFlow extends AbstractFlow<ChannelFlow> 
    implements Comparable<ChannelFlow>, ArgsHandlerSource {
    
    interface Publisher {
        public void publishChannelAtIdle(final URI domain, final ChannelFlow channelFlow);
        public void publishChannelNolongerIdle(final URI domain, final ChannelFlow channelFlow);

        public void publishChannelAtBinded(final ChannelFlow channelFlow);
        public void publishChannelNolongerBinded(final ChannelFlow channelFlow);
        
        public void publishChannelAtInactive(final ChannelFlow channelFlow);
        public void publishChannelNolongerInactive(final ChannelFlow channelFlow);
        
    }
    
    interface Toolkit {
        public URI genDomainByURI(final URI uri);
        public Channel newChannel();
    }
    
    static final String NOTIFY_CHANNEL_FOR_BINDING_ABORT = "_notify_channel_for_binding_abort";
    
    private static final Logger LOG = LoggerFactory
            .getLogger(ChannelFlow.class);

    ChannelFlow(final Publisher publisher, final Toolkit toolkit, final BytesPool bytesPool) {
        this._publisher = publisher;
        this._toolkit = toolkit;
        this._bytesPool = bytesPool;
    }

    @Override
    public ArgsHandler getArgsHandler() {
        return ArgsHandler.Consts._REFCOUNTED_ARGS_GUARD;
    }
    
    final BizStep INACTIVE = new BizStep(
            "httpchannel.INACTIVE")
            .handler(selfInvoker("inactiveOnBindWithGuide"))
            .handler(selfInvoker("inactiveOnPublishState"))
            .freeze();

    private final BizStep BINDED_CONNECTING = new BizStep(
            "httpchannel.BINDED_CONNECTING")
            .handler(selfInvoker("bindedConnectingOnBindWithGuide"))
            .handler(selfInvoker("bindedConnectingOnBindingAbort"))
            .handler(selfInvoker("bindedOnChannelConnectComplete"))
            .handler(selfInvoker("bindedConnectingOnActive"))
            .handler(selfInvoker("bindedConnectingOnDetach"))
            .handler(selfInvoker("bindedOnPublishState"))
            .freeze();

    private final BizStep BINDED_ACTIVED = new BizStep(
            "httpchannel.BINDED_ACTIVED")
            .handler(selfInvoker("bindedActivedOnBindWithGuide"))
            .handler(selfInvoker("bindedActivedOnBindingAbort"))
            .handler(selfInvoker("bindedOnInactive"))
            .handler(selfInvoker("bindedSendHttpRequest"))
            .handler(selfInvoker("bindedActivedOnDetach"))
            .handler(selfInvoker("bindedOnPublishState"))
            .freeze();

    private final BizStep BINDED_TRANSACTING = new BizStep(
            "httpchannel.BINDED_TRANSACTING")
            .handler(selfInvoker("bindedTransactingOnBindWithGuide"))
            .handler(selfInvoker("bindedOnInactive"))
            .handler(selfInvoker("bindedResponseReceived"))
            .handler(selfInvoker("bindedContentReceived"))
            .handler(selfInvoker("bindedLastContentReceived"))
            .handler(selfInvoker("bindedTransactingOnDetach"))
            .handler(selfInvoker("bindedOnPublishState"))
            .freeze();

    private final BizStep IDLE_CONNECTING = new BizStep(
            "httpchannel.IDLE_CONNECTING")
            .handler(selfInvoker("idleOnChannelConnectComplete"))
            .handler(selfInvoker("idleConnectingOnBindWithGuide"))
            .handler(selfInvoker("idleConnectingOnActive"))
            .handler(selfInvoker("idleOnPublishState"))
            .freeze();

    private final BizStep IDLE_ACTIVED = new BizStep(
            "httpchannel.IDLE_ACTIVED")
            .handler(selfInvoker("idleActivedOnBindWithGuide"))
            .handler(selfInvoker("idleActivedOnInactive"))
            .handler(selfInvoker("idleOnPublishState"))
            .freeze();

    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_PUBLISH_STATE)
    private BizStep inactiveOnPublishState() {
        this._publisher.publishChannelAtInactive(this);
        return this.currentEventHandler();
    }

    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_PUBLISH_STATE)
    private BizStep bindedOnPublishState() {
        this._publisher.publishChannelAtBinded(this);
        return this.currentEventHandler();
    }
    
    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_PUBLISH_STATE)
    private BizStep idleOnPublishState() {
        this._publisher.publishChannelAtIdle(this._domain, this);
        return this.currentEventHandler();
    }
    
    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE)
    private BizStep inactiveOnBindWithGuide(final EventReceiver guideReceiver, final Requirement requirement)
            throws Exception {
        notifyGuideForBinded(guideReceiver);
        createChannelAndConnectBy(guideReceiver, requirement);
        this._publisher.publishChannelNolongerInactive(this);
        this._publisher.publishChannelAtBinded(this);

        return this.BINDED_CONNECTING;
    }

    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE)
    private BizStep bindedConnectingOnBindWithGuide(final EventReceiver guideReceiver, final Requirement requirement)
            throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("channelFlow({})/{}/{} already binded guideFlow({}), but interrupt by high priority guideFlow({})",
                    this, currentEventHandler().getName(), currentEvent(),
                    this._guideReceiver, guideReceiver);
        }
        
        notifyGuideForChannelLostAndUnbind();
        notifyGuideForBinded(guideReceiver);
        
        final URI toBindedDomain = this._toolkit.genDomainByURI(requirement.uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) guideFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedGuideFlow(guideReceiver, requirement);
            this._publisher.publishChannelAtBinded(this);
            return currentEventHandler();
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) guideFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(guideReceiver, requirement);
            this._publisher.publishChannelAtBinded(this);
            return this.BINDED_CONNECTING;
        }
    }

    @OnEvent(event = NOTIFY_CHANNEL_FOR_BINDING_ABORT)
    private BizStep bindedConnectingOnBindingAbort(final int guideBindingId) {
        if (!isValidGuideBindingId(guideBindingId)) {
            return currentEventHandler();
        }
        resetBindedGuideFlow();
        this._publisher.publishChannelNolongerBinded(this);
        this._publisher.publishChannelAtIdle(this._domain, this);
        return this.IDLE_CONNECTING;
    }

    @OnEvent(event = "operationComplete")
    private BizStep bindedOnChannelConnectComplete(
            final ChannelFuture future) throws Exception {
        if (!isCurrentChannelResult(future)) {
            LOG.warn("bindedOnChannelConnectComplete: current uri:{} receive !NOT! current connect result for channel({}",
                    this._uri, future.channel());
            // just ignore
            return currentEventHandler();
        }
        if (!future.isSuccess()) {
            // future.isSuccess() will handle by
            // NettyEvents.CHANNEL_ACTIVE event
            // so just handle failed case
            LOG.warn("uri:{}'s channel({}) connect failed, detail: {}", 
                    this._uri, this._channel, ExceptionUtils.exception2detail( future.cause()));
            notifyGuideForChannelLostAndUnbind();
            resetBindedGuideFlow();
            this._publisher.publishChannelNolongerBinded(this);
            this._publisher.publishChannelAtInactive(this);
            return this.INACTIVE;
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("connect to uri:{} succeed", this._uri);
            }
            return currentEventHandler();
        }
    }

    @OnEvent(event = NettyEvents.CHANNEL_ACTIVE)
    private BizStep bindedConnectingOnActive(
            final ChannelHandlerContext ctx) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("channelFlow({})/{}/{} Actived by channel({})",
                    this, currentEventHandler().getName(), currentEvent(), ctx.channel());
        }
        notifyGuideForHttpClientObtained();
        return this.BINDED_ACTIVED;
    }

    @OnEvent(event = "detach")
    private BizStep bindedConnectingOnDetach(final int guideBindingId) {
        if (!isValidGuideBindingId(guideBindingId)) {
            return currentEventHandler();
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("ChannelFlow({}) has been detach.", this);
        }
        resetBindedGuideFlow();
        this._publisher.publishChannelNolongerBinded(this);
        this._publisher.publishChannelAtIdle(this._domain, this);
        return this.IDLE_CONNECTING;
    }

    @OnEvent(event = NettyEvents.CHANNEL_INACTIVE)
    private BizStep bindedOnInactive(final ChannelHandlerContext ctx)
            throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("channel for {} closed.", this._uri);
        }
        notifyGuideForChannelLostAndUnbind();
        resetBindedGuideFlow();
        this._publisher.publishChannelNolongerBinded(this);
        this._publisher.publishChannelAtInactive(this);
        return this.INACTIVE;
    }

    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE)
    private BizStep bindedActivedOnBindWithGuide(final EventReceiver guideReceiver, final Requirement requirement)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("channelFlow({})/{}/{} already binded guideFlow({}), but interrupt by high priority guideFlow({})",
                    this, currentEventHandler().getName(), currentEvent(),
                    this._guideReceiver, guideReceiver);
        }
        
        notifyGuideForChannelLostAndUnbind();
        notifyGuideForBinded(guideReceiver);
        
        final URI toBindedDomain = this._toolkit.genDomainByURI(requirement.uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) guideFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedGuideFlow(guideReceiver, requirement);
            notifyGuideForHttpClientObtained();
            this._publisher.publishChannelAtBinded(this);
            return currentEventHandler();
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) guideFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(guideReceiver, requirement);
            this._publisher.publishChannelAtBinded(this);
            return this.BINDED_CONNECTING;
        }
    }
    
    @OnEvent(event = NOTIFY_CHANNEL_FOR_BINDING_ABORT)
    private BizStep bindedActivedOnBindingAbort(final int guideBindingId) {
        if (!isValidGuideBindingId(guideBindingId)) {
            return currentEventHandler();
        }
        resetBindedGuideFlow();
        this._publisher.publishChannelNolongerBinded(this);
        this._publisher.publishChannelAtIdle(this._domain, this);
        return this.IDLE_ACTIVED;
    }

    @OnEvent(event = "detach")
    private BizStep bindedActivedOnDetach(final int guideBindingId) {
        if (!isValidGuideBindingId(guideBindingId)) {
            return currentEventHandler();
        }
        
        resetBindedGuideFlow();
        this._publisher.publishChannelNolongerBinded(this);
        this._publisher.publishChannelAtIdle(this._domain, this);
        return this.IDLE_ACTIVED;
    }

    @OnEvent(event = "sendHttpRequest")
    private BizStep bindedSendHttpRequest(
            final int currentHttpClientId,
            final Object userCtx, 
            final HttpRequest request,
            final HttpReactor<Object> reactor
            ) {
        if ( !isValidHttpClientId(currentHttpClientId) ) {
            return currentEventHandler();
        }
        this._userCtx = userCtx;
        this._httpReactor = reactor;
        
        request.headers().set(HttpHeaders.Names.CONNECTION,
                HttpHeaders.Values.KEEP_ALIVE);
        this._channel.writeAndFlush(request);
        if (LOG.isDebugEnabled()) {
            LOG.debug("({})/{}/{}: sendHttpRequest: {}", this, currentEventHandler()
                    .getName(), currentEvent(), request);
        }
        return this.BINDED_TRANSACTING;
    }

    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE)
    private BizStep bindedTransactingOnBindWithGuide(final EventReceiver guideReceiver, final Requirement requirement)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "channelFlow({})/{}/{} already binded guideFlow({}), but interrupt by high priority guideFlow({})",
                    this, currentEventHandler().getName(), currentEvent(),
                    this._guideReceiver, guideReceiver);
        }

        notifyGuideForChannelLostAndUnbind();

        resetBindedGuideFlow();
        notifyGuideForBinded(guideReceiver);

        // close detach previous channel and re-try
        closeAndDetachCurrentChannel();
        createChannelAndConnectBy(guideReceiver, requirement);
        this._publisher.publishChannelAtBinded(this);

        return this.BINDED_CONNECTING;
    }
    
    @OnEvent(event = HttpEvents.HTTPRESPONSERECEIVED)
    private BizStep bindedResponseReceived(
            final ChannelHandlerContext ctx, final HttpResponse response) {
        if (null != this._httpReactor) {
            try {
                this._httpReactor.onHttpResponseReceived(this._userCtx, response);
            } catch (Throwable e) {
                LOG.warn("exception when invoke uri({})/ctx({})'s onHttpResponseReceived, detail:{}",
                        this._uri, this._userCtx, ExceptionUtils.exception2detail(e));
            }
        } else {
            LOG.warn("uri:{} response received with internal error bcs non-reactor",
                    this._uri);
        }

        if (!HttpUtils.isHttpResponseHasMoreContent(response)) {
            return this.BINDED_ACTIVED;
        } else {
            return currentEventHandler();
        }
    }

    @OnEvent(event = HttpEvents.HTTPCONTENTRECEIVED)
    private BizStep bindedContentReceived(final ChannelHandlerContext ctx,
            final Blob blob) {
        if (null != this._httpReactor) {
            try {
                this._httpReactor.onHttpContentReceived(this._userCtx, blob);
            } catch (Throwable e) {
                LOG.warn("exception when invoke uri({})/ctx({})'s onHttpContentReceived, detail:{}",
                        this._uri, this._userCtx, ExceptionUtils.exception2detail(e));
            }
        } else {
            LOG.warn("uri:{} content received with internal error bcs non-reactor",
                    this._uri);
        }

        return currentEventHandler();
    }

    @OnEvent(event = HttpEvents.LASTHTTPCONTENTRECEIVED)
    private BizStep bindedLastContentReceived(
            final ChannelHandlerContext ctx, final Blob blob)
            throws Exception {
        if (null != this._httpReactor) {
            try {
                this._httpReactor.onLastHttpContentReceived(this._userCtx, blob);
            } catch (Throwable e) {
                LOG.warn("exception when invoke uri({})/ctx({})'s onLastHttpContentReceived, detail:{}",
                        this._uri, this._userCtx, ExceptionUtils.exception2detail(e));
            }
        } else {
            LOG.warn("uri:{} last content received with internal error bcs non-reactor",
                    this._uri);
        }

        return this.BINDED_ACTIVED;
    }

    @OnEvent(event = "detach")
    private BizStep bindedTransactingOnDetach(final int guideBindingId) {
        if (!isValidGuideBindingId(guideBindingId)) {
            return currentEventHandler();
        }
        
        closeAndDetachCurrentChannel();
        resetBindedGuideFlow();
        this._publisher.publishChannelNolongerBinded(this);
        this._publisher.publishChannelAtInactive(this);
        return this.INACTIVE;
    }

    @OnEvent(event = "operationComplete")
    private BizStep idleOnChannelConnectComplete(
            final ChannelFuture future) {
        if (!isCurrentChannelResult(future)) {
            LOG.warn("idleOnChannelConnectComplete: domain:{} receive !NOT! current connect result for channel({}",
                    this._domain, future.channel());
            // just ignore
            return currentEventHandler();
        }
        if (!future.isSuccess()) {
            // future.isSuccess() will handle by
            // NettyEvents.CHANNEL_ACTIVE event
            // so just handle failed case
            LOG.warn("uri:{}'s channel({}) connect failed, detail: {}", 
                    this._uri, this._channel, 
                    ExceptionUtils.exception2detail( future.cause()));
            this._publisher.publishChannelNolongerIdle(this._domain, this);
            this._publisher.publishChannelAtInactive(this);
            return this.INACTIVE;
        } else {
            return currentEventHandler();
        }
    }

    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE)
    private BizStep idleConnectingOnBindWithGuide(final EventReceiver guideReceiver, final Requirement requirement)
            throws Exception {
        notifyGuideForBinded(guideReceiver);
        final URI toBindedDomain = this._toolkit.genDomainByURI(requirement.uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) guideFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedGuideFlow(guideReceiver, requirement);
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) guideFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(guideReceiver, requirement);
        }
        this._publisher.publishChannelNolongerIdle(this._domain, this);
        this._publisher.publishChannelAtBinded(this);
        return this.BINDED_CONNECTING;
    }

    @OnEvent(event = NettyEvents.CHANNEL_ACTIVE)
    private BizStep idleConnectingOnActive(final ChannelHandlerContext ctx) {
        return this.IDLE_ACTIVED;
    }

    @OnEvent(event = FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE)
    private BizStep idleActivedOnBindWithGuide(final EventReceiver guideReceiver, final Requirement requirement)
            throws Exception {
        this._publisher.publishChannelNolongerIdle(this._domain, this);
        notifyGuideForBinded(guideReceiver);
        final URI toBindedDomain = this._toolkit.genDomainByURI(requirement.uri());
        if (isCurrentDomainEquals( toBindedDomain )) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the SAME domain({}) guideFlow, channel({}) can be reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            updateBindedGuideFlow(guideReceiver, requirement);
            notifyGuideForHttpClientObtained();
            this._publisher.publishChannelAtBinded(this);
            return this.BINDED_ACTIVED;
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelFlow({})/{}/{} binded the OTHER domain({}) guideFlow, channel({}) can !NOT! reused",
                        this, currentEventHandler().getName(), currentEvent(),
                        toBindedDomain, this._channel);
            }
            // close detach previous channel and re-try
            closeAndDetachCurrentChannel();
            createChannelAndConnectBy(guideReceiver, requirement);
            this._publisher.publishChannelAtBinded(this);
            return this.BINDED_CONNECTING;
        }
    }

    @OnEvent(event = NettyEvents.CHANNEL_INACTIVE)
    private BizStep idleActivedOnInactive(final ChannelHandlerContext ctx) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("IDLE channelFlow({}) closed.", this);
        }
        this._publisher.publishChannelNolongerIdle(this._domain, this);
        this._publisher.publishChannelAtInactive(this);
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

    private boolean isValidGuideBindingId(final int guideBindingId) {
        final boolean ret = this._guideBindingId.isValidId(guideBindingId);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "ChannelFlow({})/{}/{}: special guide binding id({}) is !NOT! current guide binding id ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        guideBindingId, this._guideBindingId);
            }
        }
        return ret;
    }

    private boolean isValidHttpClientId(final int httpClientId) {
        final boolean ret = this._httpClientId.isValidId(httpClientId);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "ChannelFlow({})/{}/{}: special httpclient id({}) is !NOT! current http client id ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        httpClientId, this._httpClientId);
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
     * @param guideFlow
     */
    private void createChannelAndConnectBy(final EventReceiver guideReceiver, final Requirement requirement) {
        updateBindedGuideFlow(guideReceiver, requirement);

        this._channel = this._toolkit.newChannel();
        this._channelDetacher = HttpUtils.addHttpCodecToChannel(
                this._channel,
                this._domain, 
                this._bytesPool,
                this.selfEventReceiver());

        this._connectFuture = this._channel.connect(new InetSocketAddress(
                this._domain.getHost(), this._domain.getPort()));
        this._connectFuture.addListener(genConnectListener());
    }

    private void updateBindedGuideFlow(final EventReceiver guideReceiver, final Requirement requirement) {
        this._uri = requirement.uri();
        this._domain = this._toolkit.genDomainByURI(this._uri);
        this._requirement = new HttpRequirementImpl<ChannelFlow>(requirement, this);
        this._guideReceiver = guideReceiver;
        this._httpReactor = null;
    }

    private void resetBindedGuideFlow() {
        this._requirement = null;
        this._guideReceiver = null;
        this._httpReactor = null;
    }

    /**
     * @throws Exception
     */
    private void closeAndDetachCurrentChannel() {
        try {
            if (null != this._connectFuture && !this._connectFuture.isDone()) {
                this._connectFuture.cancel(false);
            }
            if (null != this._channel) {
                this._channel.close();
            }
            if (null != this._channelDetacher) {
                try {
                    this._channelDetacher.detach();
                } catch (Throwable e) {
                    LOG.warn("exception when _channelDetacher.detach, detail:{}", 
                            ExceptionUtils.exception2detail(e));
                }
            }
        }
        finally {
            this._connectFuture = null;
            this._channel = null;
            this._channelDetacher = null;
        }
    }

    /**
     * @throws Exception
     */
    private void notifyGuideForChannelLostAndUnbind() throws Exception {
        if (null != this._guideReceiver) {
            this._guideReceiver.acceptEvent(FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_LOST);
            this._guideReceiver = null;
        }
    }

    private void notifyGuideForBinded(final EventReceiver guideReceiver)
            throws Exception {
        final int guideBindingId = this._guideBindingId.updateIdAndGet();
        guideReceiver.acceptEvent(
                new AbstractUnhandleAware(FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_BINDED) {
                    @Override
                    public void onEventUnhandle(
                            final String event,
                            final Object... args) throws Exception {
                        selfEventReceiver().acceptEvent(NOTIFY_CHANNEL_FOR_BINDING_ABORT,
                                guideBindingId);
                    }
                }, 
                this.selfEventReceiver(), 
                new Detachable() {
                    @Override
                    public void detach() throws Exception {
                        selfEventReceiver().acceptEvent("detach", guideBindingId);
                    }});
    }

    private void notifyGuideForHttpClientObtained() {
        if (null != this._guideReceiver) {
            try {
                this._guideReceiver.acceptEvent(FlowEvents.NOTIFY_GUIDE_FOR_HTTPCLIENT_OBTAINED, 
                        generateHttpClientFor(this._httpClientId.updateIdAndGet()));
            } catch (Throwable e) {
                LOG.warn("exception when NOTIFY_GUIDE_FOR_HTTPCLIENT_OBTAINED to guide({}), detail:{}",
                        this._guideReceiver, ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    private HttpClient generateHttpClientFor(final int currentHttpClientId) {
        return new HttpClient() {
            @Override
            public <CTX> void sendHttpRequest(
                    final CTX userCtx,
                    final HttpRequest request, 
                    final HttpReactor<CTX> reactor)
                    throws Exception {
                selfEventReceiver().acceptEvent(
                        "sendHttpRequest", currentHttpClientId, userCtx, request, reactor);
            }
        };
    }

    public URI bindedDomain() {
        return this._domain;
    }
    
    public HttpRequirementImpl<ChannelFlow> bindedRequirement() {
        return this._requirement;
    }
    
    @Override
    public String toString() {
        return "ChannelFlow [id=" + _id + ", state("
                + currentEventHandler().getName() + "), channel=" + _channel
                + ", bindedRequirement=" + _requirement + ", domain=" + _domain
                + ", guideBindingId=" + _guideBindingId
                + ", httpClientId=" + _httpClientId
                + ", channelDetacher("
                + (null != _channelDetacher ? "not null" : "null")
                + ")/connectFuture("
                + (null != _connectFuture ? "not null" : "null") + ")/reactor("
                + (null != _httpReactor ? "not null" : "null")
                + "), guideReceiver=" + _guideReceiver + "]";
    }
    
    private final ValidationId _httpClientId = new ValidationId();
    private final ValidationId _guideBindingId = new ValidationId();
    private final Publisher _publisher;
    private final Toolkit _toolkit;
    private final BytesPool _bytesPool;
    private Channel _channel;
    private Detachable _channelDetacher;
    private ChannelFuture _connectFuture;
    private URI _uri;
    private URI _domain;
    private Object _userCtx;
    private HttpReactor<Object> _httpReactor = null;
    private EventReceiver _guideReceiver = null;
    private volatile HttpRequirementImpl<ChannelFlow> _requirement = null;

    private final int _id = _FLOW_IDSRC.getAndIncrement();

    private static final AtomicInteger _FLOW_IDSRC = new AtomicInteger(0);
    
    @Override
    public int compareTo(final ChannelFlow o) {
        return this._id - o._id;
    }
}
