/**
 * 
 */
package org.jocean.httpclient.impl;

import java.util.concurrent.atomic.AtomicBoolean;

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
import org.jocean.idiom.ValidationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
class GuideFlow extends AbstractFlow<GuideFlow> implements Comparable<GuideFlow> {
    
    interface Publisher {
        public void publishGuideAtPending(final GuideFlow guideFlow);
        public void publishGuideLeavePending(final GuideFlow guideFlow);
    }
    
    interface Channels {
        public EventReceiver[] currentChannelsSnapshot();
    }
    
    private final static String[] _STATUS_AS_STRING = new String[]{"IDLE_AND_MATCH", "INACTIVE", "IDLE_BUT_NOT_MATCH", "CAN_BE_INTERRUPTED"};
    //  TODO
    //  ~~1. remove guid from pending list~~
    //  ~~2. record current channel to channels~~
    //  ~~3. add idle channel global~~
    interface ChannelRecommendReactor {
        static final int IDLE_AND_MATCH = 0;
        static final int INACTIVE = 1;
        static final int IDLE_BUT_NOT_MATCH = 2;
        static final int CAN_BE_INTERRUPTED = 3;
        
        public boolean isRecommendInProgress();
        public void recommendChannel(
                final int status, // idle, binded or ...
                final int recommendId,// id to mark this recommend action
                final EventReceiver channelReceiver);
    }
    
    //  request recommend channels event
    //  params: Requirement  ChannelRecommendReactor

    
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
                LOG.trace("guideFlow({})/{}/{} has been detached.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent());
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
            _publisher.publishGuideLeavePending(GuideFlow.this);
            notifyHttpLost();
            return null;
        }
        
        @OnEvent(event = FlowEvents.NOTIFY_GUIDE_START_SELECTING) 
        private BizStep startSelecting(final Channels channels)  {
            final EventReceiver[] channelReceivers = channels.currentChannelsSnapshot();
            if ( null == channelReceivers 
                    || channelReceivers.length == 0) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("guideFlow({})/{}/{} has no channels, abort channel selecting.", 
                            GuideFlow.this, currentEventHandler().getName(), currentEvent());
                }
                //  当前没有可用 channel
                return currentEventHandler();
            }
            
            final AtomicBoolean isRecommendInProgress = new AtomicBoolean(true);
            
            final int validId = _selectId.updateIdAndGet();
            
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} start channel selecting(id:{}) from {} channels.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent(), validId, channelReceivers.length);
            }
            
            final ChannelRecommendReactor reactor = new ChannelRecommendReactor() {

                @Override
                public boolean isRecommendInProgress() {
                    return isRecommendInProgress.get();
                }

                @Override
                public void recommendChannel(
                        final int status,
                        final int recommendId,// id to mark this recommend action
                        final EventReceiver channelReceiver) {
                    try {
                        selfEventReceiver().acceptEvent("onRecommendChannel", validId, status, recommendId, channelReceiver);
                    } catch (Throwable e) {
                        LOG.warn("exception when emit onRecommendChannel, detail:{}", 
                                ExceptionUtils.exception2detail(e));
                    }
                }};
            
            for ( EventReceiver receiver : channelReceivers ) {
                try {
                    receiver.acceptEvent(new AbstractUnhandleAware(FlowEvents.RECOMMEND_CHANNEL_FOR_BINDING) {

                        @Override
                        public void onEventUnhandle(String event, Object... args)
                                throws Exception {
                            try {
                                selfEventReceiver().acceptEvent("onRecommendFailed", validId);
                            } catch (Throwable e) {
                                LOG.warn("exception when emit onRecommendFailed, detail:{}", 
                                        ExceptionUtils.exception2detail(e));
                            }
                        }}, _requirement, reactor);
                } catch (Throwable e) {
                    LOG.warn("exception when emit FlowEvents.RECOMMEND_CHANNEL_FOR_BINDING, detail:{}", 
                            ExceptionUtils.exception2detail(e));
                }
            }
            
            return new SELECTING(isRecommendInProgress, channelReceivers.length).freeze();
        }
    }
    .freeze();

    private final class SELECTING extends BizStep {
        public SELECTING(final AtomicBoolean isRecommendInProgress, final int candidateCount) {
            super("httpguide.SELECTING");
            this._isRecommendInProgress = isRecommendInProgress;
            this._candidateCount = candidateCount;
        }

        @OnEvent(event = "detach")
        private BizStep onDetach() {
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} has been detached.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent());
            }
            this._isRecommendInProgress.set(false);
            _publisher.publishGuideLeavePending(GuideFlow.this);
            notifyHttpLost();
            return null;
        }
        
        @OnEvent(event = "onRecommendChannel") 
        private BizStep onRecommendChannel(
                final int validId,
                final int status,
                final int recommendId, 
                final EventReceiver channelReceiver)  {
            if ( !isValidSelectId(validId) ) {
                return currentEventHandler();
            }
            
            this._candidateCount--;
            if ( ChannelRecommendReactor.IDLE_AND_MATCH == status ) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("guideFlow({})/{}/{} (id:{}) start bind IDLE_AND_MATCH channel {}, candidateCount {}.", 
                            GuideFlow.this, currentEventHandler().getName(), currentEvent(), validId, channelReceiver, this._candidateCount);
                }
                return startBindToChannel(recommendId, channelReceiver);
            }
            else {
                // record channel's event receiver
                this._recommendIds[status-1] = recommendId;
                this._recommendChannels[status-1] = channelReceiver;
            }
            //  record candidate
            return checkCandidateCountAndReturnNextState();
        }

        @OnEvent(event = "onRecommendFailed") 
        private BizStep onRecommendFailed(
                final int validId)  {
            if ( !isValidSelectId(validId) ) {
                return currentEventHandler();
            }
            
            this._candidateCount--;
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} (id:{}) on recommend failed, candidateCount {}.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent(), validId, this._candidateCount);
            }
            return checkCandidateCountAndReturnNextState();
        }
        
        private BizStep checkCandidateCountAndReturnNextState() {
            if ( this._candidateCount > 0 ) {
                return currentEventHandler();
            }
            else {
                return selectChannelAndReturnNextState();
            }
        }
        
        private BizStep selectChannelAndReturnNextState() {
            for ( int i = 0; i < 3; i++) {
                if ( null != this._recommendChannels[i]) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("guideFlow({})/{}/{} start bind channel {} with status {}.", 
                                GuideFlow.this, currentEventHandler().getName(), currentEvent(), this._recommendChannels[i], _STATUS_AS_STRING[i+1]);
                    }
                    return startBindToChannel( this._recommendIds[i], this._recommendChannels[i]);
                }
            }
            this._isRecommendInProgress.set(false);
            return PENDING;
        }
        
        private BizStep startBindToChannel(
                final int recommendId, 
                final EventReceiver channelEventReceiver) {
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("guideFlow({})/{}/{} start bind to channel:{}", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent(), channelEventReceiver );
            }
            
            this._isRecommendInProgress.set(false);
            try {
                channelEventReceiver.acceptEvent(
                        new AbstractUnhandleAware(FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE) {
                            @Override
                            public void onEventUnhandle( final String event, final Object... args) 
                                    throws Exception {
                                // means channel bind failed
                                selfEventReceiver().acceptEvent(NOTIFY_GUIDE_FOR_BINDING_ABORT, channelEventReceiver);
                            }},
                        recommendId,
                        selfEventReceiver(),
                        _requirement);
            } catch (Throwable e) {
                LOG.warn("exception when emit FlowEvents.REQUEST_CHANNEL_BIND_WITH_GUIDE, detail:{}", 
                        ExceptionUtils.exception2detail(e));
            }

            return ATTACHING;
        }
        
        private boolean isValidSelectId(final int selectId) {
            final boolean ret = _selectId.isValidId(selectId);
            if (!ret) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("GuidFlow({})/{}/{}: special select id({}) is !NOT! current select id ({}), just ignore.",
                            this, currentEventHandler().getName(), currentEvent(),
                            selectId, _selectId);
                }
            }
            return ret;
        }
        
        private int _candidateCount;
        private final AtomicBoolean _isRecommendInProgress;
        private final EventReceiver[] _recommendChannels = new EventReceiver[3];
        private final int[] _recommendIds = new int[3];
    }
    
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
            _publisher.publishGuideLeavePending(GuideFlow.this);
            return ATTACHED;
        }
        
        @OnEvent(event = NOTIFY_GUIDE_FOR_BINDING_ABORT)
        private BizStep onBindToChannelAbort(final EventReceiver channelEventReceiver) 
                throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("guideFlow({}) bind channel({}) failed. try to re-attach", 
                        GuideFlow.this, channelEventReceiver);
            }
            return PENDING;
        }
        
        @OnEvent(event = "detach")
        private BizStep onDetach() throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("guideFlow({})/{}/{} has been detached.", 
                        GuideFlow.this, currentEventHandler().getName(), currentEvent());
            }
            _publisher.publishGuideLeavePending(GuideFlow.this);
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
                + ", guideReactor(" + 
                (null != _guideReactor ? "not null" : "null") 
                + "), channelReceiver(" + 
                (null != _channelReceiver ? "not null" : "null") 
                + ")]";
    }

    private final ValidationId _selectId = new ValidationId();
    
    private final Publisher _publisher;
    private Object _userCtx;
    private GuideReactor<Object> _guideReactor;
    private Guide.Requirement _requirement;
    private EventReceiver _channelReceiver;
    private Detachable _channelDetacher;
}
