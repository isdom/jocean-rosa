/**
 * 
 */
package org.jocean.httpclient.impl;

import io.netty.channel.Channel;

import java.net.URI;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.netty.NettyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class MediatorFlow extends AbstractFlow<MediatorFlow> {
    
//    private static final String PROCESS_PENDING_GUIDES = "_check_pending_guides";
    
    private static final Logger LOG = LoggerFactory
            .getLogger(MediatorFlow.class);
    
    public MediatorFlow(
            final BytesPool bytesPool,
            final EventReceiverSource source,
            final NettyClient client, 
            final int maxActived) {
        this._bytesPool = bytesPool;
        this._source = source;
        this._client = client;
        this._maxChannelCount = maxActived;
    }

    public Guide createHttpClientGuide() {
        final GuideFlow flow = new GuideFlow(
                this.queryInterfaceInstance(GuideFlow.Publisher.class));
        this._source.create(flow, flow.UNOBTAIN );
        return flow.queryInterfaceInstance(Guide.class);
    }
    
    final public BizStep DISPATCH = new BizStep("httpmediator.DISPATCH")
        .handler(handlersOf(this))
        .freeze();

//    @OnEvent(event = PROCESS_PENDING_GUIDES)
//    private BizStep doProcessPendingGuides() {
//        if ( LOG.isDebugEnabled() ) {
//            LOG.debug("doCheckPendings when _pendingGuides's size:({})", this._pendingGuides.size());
//        }
//        
//        this._processPendingGuides.set(false);
//        
//        int succeedCount = 0;
//        int failedCount = 0;
//        int skipedCount = 0;
//        
//        while ( !this._pendingGuides.isEmpty() ) {
//            final GuideFlow guideFlow = this._pendingGuides.poll();
//            if ( null != guideFlow ) {
//                if ( obtainHttpChannelForGuide(guideFlow) ) {
//                    if ( LOG.isTraceEnabled() ) {
//                        LOG.trace("obtain HttpChannel: for GuideFlow({}) succeed.", guideFlow);
//                    }
//                    succeedCount++;
//                }
//                else {
//                    if ( LOG.isTraceEnabled() ) {
//                        LOG.trace("obtain HttpChannel: for GuideFlow({}) failed.", guideFlow);
//                    }
//                    skipedCount = this._pendingGuides.size();
//                    this._pendingGuides.add(guideFlow);
//                    failedCount++;
//                    break;
//                }
//            }
//        }
//        if ( LOG.isDebugEnabled() ) {
//            LOG.debug("doCheckPendings result, succeed:{}/failed:{}/skipped:{}", 
//                    succeedCount, failedCount, skipedCount);
//        }
//        return this.currentEventHandler();
//    }
    
    @OnEvent(event = "publishGuideAtPending")
    private BizStep onGuideAtPending(final GuideFlow flow) {
        if ( !this._pendingGuides.contains(flow) ) {
            if ( this._pendingGuides.add(flow) ) {
                if ( LOG.isTraceEnabled() ) {
                    LOG.trace("Pendings: add GuideFlow({}) to pending queue succeed", flow);
                }
                if ( this._pendingGuides.peek() == flow ) {
                    //  如果被增加的 Guide 是优先级最高的Guide 则直接发起 匹配 channel 的事件
                    notifyGuidStartSelecting(flow);
                }
//                launchProcessPendingGuides();
            }
            else {
                if ( LOG.isTraceEnabled() ) {
                    LOG.trace("Pendings: add GuideFlow({}) to pending queue failed", flow);
                }
            }
        }
        
        return this.currentEventHandler();
    }

    /**
     * @param flow
     */
    private void notifyGuidStartSelecting(final GuideFlow flow) {
        //  check and add inactive channel
        final int currentChannelCount = this._currentChannelCount.get();
        if ( currentChannelCount < this._maxChannelCount ) {
            if ( currentChannelCount <= this._bindedChannelCount.get() ) {
                createInactiveChannelFlow();
            }
        }
        try {
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("notifyGuidStartSelecting: notify GuideFlow({}) start selecting channel", flow);
            }
            flow.selfEventReceiver().acceptEvent(FlowEvents.NOTIFY_GUIDE_START_SELECTING, this._channels);
        } catch (Throwable e) {
            LOG.warn("exception when emit FlowEvents.NOTIFY_GUIDE_START_SELECTING for guide {}, detail:{}",
                    flow, ExceptionUtils.exception2detail(e));
        }
    }

    @OnEvent(event = "publishGuideLeavePending")
    private BizStep onGuideLeavePending(final GuideFlow flow) {
        if ( this._pendingGuides.remove(flow) ) {
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("Pendings: remove GuideFlow({}) from pending queue succeed", flow);
            }
        }
        else {
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("Pendings: remove GuideFlow({}) from pending queue failed", flow);
            }
        }
        
        return this.currentEventHandler();
    }
    
//    @OnEvent(event = "publishChannelAtIdle")
//    private BizStep onChannelAtIdle(final URI domain, final ChannelFlow channelFlow) {
//        if ( getOrCreateIdleChannelPool(domain).add(channelFlow) ) {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: add ChannelFlow({}) to idle set succeed", channelFlow);
//            }
//            launchProcessPendingGuides();
//        }
//        else {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: add ChannelFlow({}) to idle set succeed", channelFlow);
//            }
//        }
//        
//        return this.currentEventHandler();
//    }
//
//    @OnEvent(event = "publishChannelNolongerIdle")
//    private BizStep onChannelNolongerIdle(final URI domain, final ChannelFlow channelFlow) {
//        removeChannelFromIdles(domain, channelFlow);
//        return this.currentEventHandler();
//    }
//
//    @OnEvent(event = "publishChannelAtBinded")
//    private BizStep onChannelAtBinded(final ChannelFlow channelFlow) {
//        if ( !this._bindedChannelRequirements.contains(channelFlow.bindedRequirement())) {
//            if ( this._bindedChannelRequirements.add(channelFlow.bindedRequirement()) ) {
//                if ( LOG.isTraceEnabled() ) {
//                    LOG.trace("HttpChannels: add ChannelFlow({}) to binded queue succeed", channelFlow);
//                }
//                launchProcessPendingGuides();
//            }
//            else {
//                if ( LOG.isTraceEnabled() ) {
//                    LOG.trace("HttpChannels: add ChannelFlow({}) to binded queue failed", channelFlow);
//                }
//            }
//        }
//        return this.currentEventHandler();
//    }
//
//    @OnEvent(event = "publishChannelNolongerBinded")
//    private BizStep onChannelNolongerBinded(final ChannelFlow channelFlow) {
//        removeChannelFromBindeds(channelFlow);
//        return this.currentEventHandler();
//    }
//    
//    @OnEvent(event = "publishChannelAtInactive")
//    private BizStep onChannelAtInactive(final ChannelFlow channelFlow) {
//        if ( this._inactiveChannels.add(channelFlow) ) {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: add ChannelFlow({}) to inactive set succeed", channelFlow);
//            }
//            launchProcessPendingGuides();
//        }
//        else {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: add ChannelFlow({}) to inactive set failed", channelFlow);
//            }
//        }
//        return this.currentEventHandler();
//    }
//
//    @OnEvent(event = "publishChannelNolongerInactive")
//    private BizStep onChannelNolongerInactive(final ChannelFlow channelFlow) {
//        removeChannelFromInactives(channelFlow);
//        return this.currentEventHandler();
//    }
    
//    private static boolean canInterruptLowPriority(final int highPriority) {
//        return (highPriority >= 0);
//    }
//
//    private static boolean canbeInterruptByHighPriority(final int lowPriority, final int highPriority) {
//        return ( lowPriority < 0 && highPriority >= 0);
//    }
    
//    private Set<ChannelFlow> getOrCreateIdleChannelPool(final URI domain) {
//        Set<ChannelFlow> pool = this._idleChannels.get(domain);
//        if ( null == pool ) {
//            pool = new HashSet<ChannelFlow>();
//            this._idleChannels.put(domain, pool);
//        }
//        
//        return  pool;
//    }
//    
//    private Set<ChannelFlow> getIdleChannelPool(final URI domain) {
//        return this._idleChannels.get(domain);
//    }
    
    private static URI genDomainByURI(final URI uri) {
        final String scheme = uri.getScheme() == null? "http" : uri.getScheme();
        final String host = uri.getHost() == null? "localhost" : uri.getHost();
        final int port = getInetPort(uri, scheme);
        
        try {
            return new URI(scheme + "://" + host + ":" + port);
        } catch (Exception e) {
            LOG.error("exception when create key for uri:({}), detail:{}", 
                    uri, ExceptionUtils.exception2detail(e));
            return null;
        }
    }
    
    private static int getInetPort(final URI uri, final String scheme) {
        if (uri.getPort() == -1) {
            if ("http".equalsIgnoreCase(scheme)) {
                return 80;
            } else if ("https".equalsIgnoreCase(scheme)) {
                return 443;
            }
            else {
                return  -1;
            }
        }
        else {
            return uri.getPort();
        }
    }
    
//    private boolean obtainHttpChannelForGuide(final GuideFlow guideFlow) {
//        if ( LOG.isTraceEnabled()) {
//            LOG.trace("try to launch pending GuideFlow({})", guideFlow);
//        }
//        
//        final ChannelFlow channelFlow = findAndReserveChannelFlow(guideFlow);
//        if ( null != channelFlow ) {
//            notifyGuideForChannelReserved(guideFlow, channelFlow);
//            return true;
//        }
//        else {
//            return false;
//        }
//    }
//    
//    private ChannelFlow findAndReserveChannelFlow(final GuideFlow guideFlow) {
//        //  step 1: check if idle channel match uri
//        //  step 2: check weather can create new channel
//        //  step 3: check if can close idle channel (connect other uri)
//        //  step 4: check if can interrupt(close) low priority channel
//        
//        ChannelFlow channelFlow = null;
//        
//        // step 1
//        channelFlow = findAndReserveIdleChannelMatch(guideFlow);
//        if (null != channelFlow) {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("found idle channel flow {}", channelFlow);
//            }
//            return  channelFlow;
//        }
//        
//        //  step 2
//        channelFlow = findAndReserveInactiveChannel(guideFlow);
//        if (null != channelFlow) {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("found inactive channel flow {}", channelFlow);
//            }
//            return  channelFlow;
//        } 
//        
//        // step 3
//        channelFlow = findAndReserveAnyIdleChannel(guideFlow);
//        if ( null != channelFlow ) {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("found idle but uri mismatch channel flow {}, try to close and attach", channelFlow);
//            }
//            return  channelFlow;
//        }
//        
//        if ( canInterruptLowPriority(guideFlow.requirement().priority()) ) {
//            // step 4: check if interrupt (close) low priority channel
//            channelFlow = findAndReserveBindedLowPriorityChannel(guideFlow);
//            if ( null != channelFlow ) {
//                if ( LOG.isTraceEnabled() ) {
//                    LOG.trace("found binded channel flow({}) try to close and attach", channelFlow);
//                }
//                return  channelFlow;
//            }
//        }
//        
//        return  null;
//    }
//
//    /**
//     * @param channelReceiver
//     * @param guideReceiver
//     */
//    private void notifyGuideForChannelReserved(
//            final GuideFlow guideFlow,
//            final ChannelFlow channelFlow) {
//        try {
//            final EventReceiver guideReceiver = guideFlow.selfEventReceiver();
//            final EventReceiver channelReceiver = channelFlow.selfEventReceiver();
//            
//            // remember its async event handle or !NOT! 
//            //  TODO removed
////            guideReceiver.acceptEvent(
////                    new AbstractUnhandleAware(FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_RESERVED) {
////                        @Override
////                        public void onEventUnhandle( final String event, final Object... args) 
////                                throws Exception {
////                            // if attch channel failed, notify channelFlow
////                            channelReceiver.acceptEvent(FlowEvents.REQUEST_CHANNEL_PUBLISH_STATE);
////                        }},
////                        channelReceiver);
//        } catch (Exception e) {
//            LOG.warn("exception when FlowEvents.NOTIFY_GUIDE_FOR_CHANNEL_RESERVED", e);
//        }
//    }
//
//    private ChannelFlow findAndReserveIdleChannelMatch(final GuideFlow guideFlow) {
//        final Set<ChannelFlow> pool = getIdleChannelPool( genDomainByURI(guideFlow.requirement().uri()) );
//        if ( null != pool && !pool.isEmpty()) {
//            final ChannelFlow ret = pool.iterator().next();
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("findAndReserveChannel: found Idle ChannelFlow({}) for guideFlow({})", 
//                        ret, guideFlow);
//            }
//            removeChannelFromIdles(ret.bindedDomain(), ret);
//            return ret;
//        } 
//        else {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("findAndReserveChannel: can't find Idle ChannelFlow for guideFlow({})", 
//                        guideFlow);
//            }
//            return null;
//        }
//    }
//
//    private ChannelFlow findAndReserveAnyIdleChannel(final GuideFlow guideFlow) {
//        final Collection<Set<ChannelFlow>> values = this._idleChannels.values();
//        for ( Set<ChannelFlow> pool : values ) {
//            if ( !pool.isEmpty() ) {
//                final ChannelFlow ret = pool.iterator().next();
//                if ( LOG.isTraceEnabled() ) {
//                    LOG.trace("findAndReserveChannel: found ANY Idle ChannelFlow({}) for guideFlow({})", 
//                            ret, guideFlow);
//                }
//                removeChannelFromIdles(ret.bindedDomain(), ret);
//                return ret;
//            }
//        }
//        if ( LOG.isTraceEnabled() ) {
//            LOG.trace("findAndReserveChannel: can't find ANY Idle ChannelFlow for guideFlow({})", 
//                    guideFlow);
//        }
//        return null;
//    }
//    
//    private ChannelFlow findAndReserveBindedLowPriorityChannel(final GuideFlow guideFlow) {
//        final HttpRequirementImpl<ChannelFlow> channelRequirement = this._bindedChannelRequirements.peek();
//        if ( null != channelRequirement ) {
//            if ( canbeInterruptByHighPriority(channelRequirement.priority(), guideFlow.requirement().priority()) ) {
//                if ( LOG.isTraceEnabled() ) {
//                    LOG.trace("findAndReserveChannel: found LOW priority ChannelFlow({}) for HIGH priority guideFlow({}), try to interrupt it's transaction", 
//                            channelRequirement.owner(), guideFlow);
//                }
//                removeChannelFromBindeds(channelRequirement.owner());
//                return  channelRequirement.owner();
//            }
//            else {
//                if ( LOG.isTraceEnabled() ) {
//                    LOG.trace("findAndReserveChannel: current lowest priority ChannelFlow is ({}), >= guideFlow({})'s priority, just pending",
//                            channelRequirement.owner(), guideFlow);
//                }
//            }
//        }
//        else {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("findAndReserveChannel: can't find any binded ChannelFlow for guideFlow({})'s priority, just pending",
//                        guideFlow);
//            }
//        }
//        return null;
//    }
//
//    private ChannelFlow findAndReserveInactiveChannel(final GuideFlow guideFlow) {
//        if ( !this._inactiveChannels.isEmpty() ) {
//            final ChannelFlow ret = this._inactiveChannels.iterator().next();
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("findAndReserveChannel: found inactive ChannelFlow({}) for guideFlow({})", 
//                        ret, guideFlow);
//            }
//            removeChannelFromInactives(ret);
//            return ret;
//        }
//        else if (!isExceedTotalChannelLimit()) {
//            final ChannelFlow ret = createInactiveChannelFlow();
//            if ( LOG.isInfoEnabled() ) {
//                LOG.info("findAndReserveChannel: create new ChannelFlow({}) for guideFlow({})", 
//                        ret, guideFlow);
//            }
//            return ret;
//        }
//        else {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("findAndReserveChannel: can't find or create ChannelFlow for guideFlow({})", 
//                        guideFlow);
//            }
//            return null;
//        }
//    }

    //  TODO
    private ChannelFlow createInactiveChannelFlow() {
        final ChannelFlow channelFlow = new ChannelFlow(
                this._channelPublisher,
//                this.queryInterfaceInstance(ChannelFlow.Publisher.class), 
                this._channelToolkit, this._bytesPool)
            .addFlowLifecycleListener(this._channelFlowLifecycleListener);
        
        this._source.create(channelFlow, channelFlow.INACTIVE );
        return channelFlow;
    }

//    private boolean isExceedTotalChannelLimit() {
//        return (this._currentChannelCount.get() >= this._maxChannelCount);
//    }
//    
//    private void launchProcessPendingGuides() {
//        if ( this._processPendingGuides.compareAndSet(false, true) ) {
//            try {
//                selfEventReceiver().acceptEvent(PROCESS_PENDING_GUIDES);
//            } catch (Exception e) {
//            }
//        }
//    }
//    
//    private void removeChannelFromIdles(final URI domain, final ChannelFlow channelFlow) {
//        final Set<ChannelFlow> pool = getIdleChannelPool(domain);
//        if ( null != pool ) {
//            if ( pool.remove(channelFlow) ) {
//                if ( LOG.isTraceEnabled() ) {
//                    LOG.trace("HttpChannels: remove ChannelFlow({}) from idle set succeed", channelFlow);
//                }
//                return;
//            }
//        }
//        if ( LOG.isTraceEnabled() ) {
//            LOG.trace("HttpChannels: remove ChannelFlow({}) from idle set failed", channelFlow);
//        }
//    }
//    
//    private void removeChannelFromBindeds(final ChannelFlow channelFlow) {
//        if ( this._bindedChannelRequirements.remove(channelFlow.bindedRequirement() ) ) {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: remove ChannelFlow({}) from binded queue succeed", channelFlow);
//            }
//        }
//        else {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: remove ChannelFlow({}) from binded queue failed", channelFlow);
//            }
//        }
//    }
//    
//    private BizStep removeChannelFromInactives(final ChannelFlow channelFlow) {
//        if ( this._inactiveChannels.remove(channelFlow) ) {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: remove ChannelFlow({}) from inactive set succeed", channelFlow);
//            }
//        }
//        else {
//            if ( LOG.isTraceEnabled() ) {
//                LOG.trace("HttpChannels: remove ChannelFlow({}) from inactive set failed", channelFlow);
//            }
//        }
//        return this.currentEventHandler();
//    }
    
    private final ChannelFlow.Toolkit _channelToolkit = new ChannelFlow.Toolkit() {
        public URI genDomainByURI(final URI uri) {
            return MediatorFlow.genDomainByURI(uri);
        }
        
        public Channel newChannel() {
            return MediatorFlow.this._client.newChannel();
        }
    };
    
    private final ChannelFlow.Publisher _channelPublisher = new ChannelFlow.Publisher() {

        @Override
        public void publishChannelBinded(ChannelFlow channelFlow) {
            _bindedChannelCount.incrementAndGet();
        }

        @Override
        public void publishChannelUnbind(
                ChannelFlow channelFlow) {
            _bindedChannelCount.decrementAndGet();
        }};
        
    private final FlowLifecycleListener<ChannelFlow> _channelFlowLifecycleListener = 
            new FlowLifecycleListener<ChannelFlow>() {
                @Override
                public void afterEventReceiverCreated(final ChannelFlow flow,
                        final EventReceiver receiver) throws Exception {
                    _currentChannelCount.incrementAndGet();
                    _channelReceivers.add(receiver);
                }
        
                @Override
                public void afterFlowDestroy(final ChannelFlow flow)
                        throws Exception {
                    _channelReceivers.remove(flow.selfEventReceiver());
                    _currentChannelCount.decrementAndGet();
                }
            };
            
    private final GuideFlow.Channels _channels = new GuideFlow.Channels() {
        @Override
        public EventReceiver[] currentChannelsSnapshot() {
            return _channelReceivers.toArray(new EventReceiver[0]);
        }};

//    private final AtomicBoolean _processPendingGuides = new AtomicBoolean(false);
            
    private final int _maxChannelCount;
    private final AtomicInteger _currentChannelCount = new AtomicInteger(0);
    private final List<EventReceiver> _channelReceivers = new CopyOnWriteArrayList<EventReceiver>();
    private final AtomicInteger _bindedChannelCount = new AtomicInteger(0);
    
    private final Queue<GuideFlow> _pendingGuides = new PriorityQueue<GuideFlow>();
    
//    //  connected but not binded _channels
//    private final Map<URI, Set<ChannelFlow>> _idleChannels = new HashMap<URI, Set<ChannelFlow>>();
//    
//    //  connected or connecting and binded _channels
//    private final Queue<HttpRequirementImpl<ChannelFlow>> _bindedChannelRequirements = 
//            new PriorityQueue<HttpRequirementImpl<ChannelFlow>>(11, Guide.ASC_COMPARATOR);
//    
//    //  inactive _channels (closed)
//    private final Set<ChannelFlow> _inactiveChannels = new HashSet<ChannelFlow>();
    
    
    private final BytesPool _bytesPool;
    private final NettyClient _client;
    private final EventReceiverSource _source;
}
