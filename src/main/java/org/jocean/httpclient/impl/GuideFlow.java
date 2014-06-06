/**
 * 
 */
package org.jocean.httpclient.impl;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.Guide.Requirement;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
class GuideFlow extends AbstractFlow<GuideFlow> implements Comparable<GuideFlow> {
    
    interface Publisher {
        public void publishGuideAtPending(final GuideFlow guideFlow);
        public void publishGuideEnd(final GuideFlow guideFlow);
    }
    
    static final String NOTIFY_GUIDE_FOR_BINDING_ABORT = "_notify_guide_for_binding_abort";
    
    private static final Logger LOG = LoggerFactory
            .getLogger(GuideFlow.class);

    GuideFlow(final Publisher publisher ) {
        this._publisher = publisher;
    }
    
    final BizStep UNOBTAIN = new BizStep(
            "httpguide.UNOBTAIN")
            .handler(selfInvoker("unobtainOnDetach"))
            .handler(selfInvoker("onObtainClient"))
            .freeze();

    private final BizStep PENDING = new BizStep(
            "httpguide.PENDING")
            .handler(selfInvoker("pendingOnDetach"))
            .handler(selfInvoker("startBindToChannel"))
            .freeze();

    private final BizStep ATTACHING = new BizStep(
            "httpguide.ATTACHING")
            .handler(selfInvoker("attachingOnBindToChannelSucceed"))
            .handler(selfInvoker("attachingOnBindToChannelAbort"))
            .handler(selfInvoker("attachingOnDetach"))
            .freeze();

    private final BizStep ATTACHED = new BizStep(
            "httpguide.ATTACHED")
            .handler(selfInvoker("attachedOnHttpClientObtained"))
            .handler(selfInvoker("attachedOnDetach"))
            .handler(selfInvoker("attachedOnChannelLost"))
            .freeze();
    
    @OnEvent(event = "detach")
    private BizStep unobtainOnDetach() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("guide(unobtain) has been released.");
        }
        return null;
    }

    @OnEvent(event = "obtainHttpClient")
    private BizStep onObtainClient(
            final Object userCtx, 
            final GuideReactor<Object> guideReactor, 
            final Requirement requirement) {
        if ( null == requirement || null == guideReactor ) {
            LOG.error("guideFlow({})/{}/{} invalid params, detail: Requirement:{} GuideReactor:{}", 
                    this, currentEventHandler().getName(), currentEvent(), requirement, guideReactor);
            throw new NullPointerException("Requirement and GuideReactor can't be null.");
        }
        this._userCtx = userCtx;
        this._requirement = new HttpRequirementImpl<GuideFlow>(requirement, this);
        this._guideReactor = guideReactor;
        this._publisher.publishGuideAtPending(this);
        return this.PENDING;
    }

    @OnEvent(event = "detach")
    private BizStep pendingOnDetach() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("guideFlow({})/{}/{} has been detached.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        this._publisher.publishGuideEnd(this);
        notifyHttpLost();
        return null;
    }

    @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_RESERVED)
    private BizStep startBindToChannel(final EventReceiver channelEventReceiver) 
            throws Exception {
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("guideFlow({})/{}/{} start bind to channel:{}", 
                    this, currentEventHandler().getName(), currentEvent(), channelEventReceiver );
        }
        
        channelEventReceiver.acceptEvent(
                new AbstractUnhandleAware(FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE) {
                    @Override
                    public void onEventUnhandle( final String event, final Object... args) 
                            throws Exception {
                        // means channel bind failed
                        selfEventReceiver().acceptEvent(NOTIFY_GUIDE_FOR_BINDING_ABORT, channelEventReceiver);
                    }},
                this.selfEventReceiver(),
                this._requirement);

        return ATTACHING;
    }

    @OnEvent(event = NOTIFY_GUIDE_FOR_BINDING_ABORT)
    private BizStep attachingOnBindToChannelAbort(final EventReceiver channelEventReceiver) 
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("guideFlow({}) bind channel({}) failed. try to re-attach", 
                    this, channelEventReceiver);
        }
        this._publisher.publishGuideAtPending(this);
        return PENDING;
    }
    
    @OnEvent(event = "detach")
    private BizStep attachingOnDetach() throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("guideFlow({})/{}/{} has been detached.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        return null;
    }

    @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_BINDED)
    private BizStep attachingOnBindToChannelSucceed(final EventReceiver channelEventReceiver, final Detachable detacher) 
            throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("guideFlow({})/{}/{} has attached channel ({}).", 
                    this, currentEventHandler().getName(), currentEvent(), channelEventReceiver);
        }
        this._channelReceiver = channelEventReceiver;
        this._channelDetacher = detacher;
        return this.ATTACHED;
    }
    
    @OnEvent(event = "detach")
    private BizStep attachedOnDetach() throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("guideFlow({})/{}/{} has been detached.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        try {
            this._channelDetacher.detach();
        }
        catch (Throwable e) {
            LOG.warn("exception when invoke _channelDetacher.detach, detail:{}",
                    ExceptionUtils.exception2detail(e));
        }
        notifyHttpLost();
        return null;
    }
    
    @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_HTTPCLIENT_OBTAINED)
    private BizStep attachedOnHttpClientObtained(final HttpClient httpClient) throws Exception {
        if (null != this._guideReactor) {
            try {
                this._guideReactor.onHttpClientObtained(this._userCtx, httpClient);
            } catch (Throwable e) {
                LOG.warn("exception when invoke onHttpClientObtained, detail:{}",
                        ExceptionUtils.exception2detail(e));
            }
        } 
        else {
            LOG.warn("OnHttpClientObtained with internal error bcs non-guide-receiver");
        }
        return currentEventHandler();
    }
    
    @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_LOST)
    private BizStep attachedOnChannelLost() throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("guideFlow({})/{}/{} channel lost.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        notifyHttpLost();
        return null;
    }

    private void notifyHttpLost() {
        if (null != this._guideReactor) {
            try {
                this._guideReactor.onHttpClientLost(this._userCtx);
            } catch (Throwable e) {
                LOG.warn("exception when invoke ctx({})'s onHttpClientLost, detail:{}", 
                        this._userCtx, ExceptionUtils.exception2detail(e));
            }
        } else {
            LOG.warn("internal error bcs null reactor");
        }
    }
    
    @Override
    public EventReceiver selfEventReceiver() {
        return super.selfEventReceiver();
    }

    public Requirement requirement() {
        return this._requirement;
    }
    
    @Override
    public int compareTo(final GuideFlow o) {
        final Guide.Requirement selfRequirement = this._requirement;
        final Guide.Requirement otherRequirement = o._requirement;
        if ( null == selfRequirement) {
            LOG.error("Guide.Requirement not set for GuideFlow({})", this);
            throw new NullPointerException("Guide.Requirement not set");
        }
        if ( null == otherRequirement) {
            LOG.error("Guide.Requirement not set for GuideFlow({})", o);
            throw new NullPointerException("Guide.Requirement not set");
        }
        return otherRequirement.priority() - selfRequirement.priority();
    }
    
    @Override
    public String toString() {
        return "GuideFlow [requirement=" + _requirement 
                + ", state(" + currentEventHandler().getName()
                + "), guideReactor(" + 
                (null != _guideReactor ? "not null" : "null") 
                + "), channel=" + _channelReceiver + "]";
    }

    private final Publisher _publisher;
    private Object _userCtx;
    private GuideReactor<Object> _guideReactor;
    private Guide.Requirement _requirement;
    private EventReceiver _channelReceiver;
    private Detachable _channelDetacher;
}
