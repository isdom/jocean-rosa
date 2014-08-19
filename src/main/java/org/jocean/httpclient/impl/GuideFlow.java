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
    
    final BizStep UNOBTAIN = new BizStep("httpguide.UNOBTAIN") {
        @OnEvent(event = "detach")
        private BizStep onDetach() {
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
                        GuideFlow.this, currentEventHandler().getName(), currentEvent(), requirement, guideReactor);
                throw new NullPointerException("Requirement and GuideReactor can't be null.");
            }
            _userCtx = userCtx;
            _requirement = new HttpRequirementImpl<GuideFlow>(requirement, GuideFlow.this);
            _guideReactor = guideReactor;
            _publisher.publishGuideAtPending(GuideFlow.this);
            return PENDING;
        }
    }
    .freeze();

    private final BizStep PENDING = new BizStep("httpguide.PENDING") {
        @OnEvent(event = "detach")
        private BizStep onDetach() {
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} has been detached.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent());
            }
            _publisher.publishGuideEnd(GuideFlow.this);
            notifyHttpLost();
            return null;
        }
        
        @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_RESERVED)
        private BizStep startBindToChannel(final EventReceiver channelEventReceiver) 
                throws Exception {
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("guideFlow({})/{}/{} start bind to channel:{}", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent(), channelEventReceiver );
            }
            
            channelEventReceiver.acceptEvent(
                    new AbstractUnhandleAware(FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE) {
                        @Override
                        public void onEventUnhandle( final String event, final Object... args) 
                                throws Exception {
                            // means channel bind failed
                            selfEventReceiver().acceptEvent(NOTIFY_GUIDE_FOR_BINDING_ABORT, channelEventReceiver);
                        }},
                    selfEventReceiver(),
                    _requirement);

            return ATTACHING;
        }
    }
    .freeze();

    private final BizStep ATTACHING = new BizStep("httpguide.ATTACHING") {
        @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_BINDED)
        private BizStep onBindToChannelSucceed(final EventReceiver channelEventReceiver, final Detachable detacher) 
                throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} has attached channel ({}).", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent(), channelEventReceiver);
            }
            _channelReceiver = channelEventReceiver;
            _channelDetacher = detacher;
            return ATTACHED;
        }
        
        @OnEvent(event = NOTIFY_GUIDE_FOR_BINDING_ABORT)
        private BizStep onBindToChannelAbort(final EventReceiver channelEventReceiver) 
                throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("guideFlow({}) bind channel({}) failed. try to re-attach", 
                        GuideFlow.this, channelEventReceiver);
            }
            _publisher.publishGuideAtPending(GuideFlow.this);
            return PENDING;
        }
        
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} has been detached.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent());
            }
            notifyHttpLost();
            return null;
        }
    }
    .freeze();

    private final BizStep ATTACHED = new BizStep("httpguide.ATTACHED") {
        @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_HTTPCLIENT_OBTAINED)
        private BizStep onHttpClientObtained(final HttpClient httpClient) throws Exception {
            if (null != _guideReactor) {
                try {
                    _guideReactor.onHttpClientObtained(_userCtx, httpClient);
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
        
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} has been detached.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent());
            }
            try {
                _channelDetacher.detach();
            }
            catch (Throwable e) {
                LOG.warn("exception when invoke _channelDetacher.detach, detail:{}",
                        ExceptionUtils.exception2detail(e));
            }
            notifyHttpLost();
            return null;
        }
        
        @OnEvent(event = FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_LOST)
        private BizStep onChannelLost() throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} channel lost.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent());
            }
            notifyHttpLost();
            return null;
        }
    }
    .freeze();

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
