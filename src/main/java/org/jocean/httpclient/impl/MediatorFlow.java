/**
 * 
 */
package org.jocean.httpclient.impl;

import io.netty.channel.Channel;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnDelayed;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.idiom.Detachable;
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
    
    private static final Logger LOG = LoggerFactory
            .getLogger(MediatorFlow.class);

    public MediatorFlow(
            final BytesPool bytesPool, 
            final EventReceiverSource source4guide,
            final EventReceiverSource source4channel, 
            final NettyClient client,
            final int maxChannelCount) {
        this._bytesPool = bytesPool;
        this._source4guide = source4guide;
        this._source4channel = source4channel;
        this._client = client;
        this._maxChannelCount = maxChannelCount;
    }

    public int getMaxChannelCount() {
        return this._maxChannelCount;
    }
    
    public int getTotalChannelCount() {
        return this._currentChannelCount.get();
    }
    
    public int getBindedChannelCount() {
        return this._bindedChannelCount.get();
    }
    
    public int getPendingGuideCount() {
        return this._pendingGuides.size();
    }
    
    public Guide createHttpClientGuide() {
        final GuideFlow flow = new GuideFlow(
                this.queryInterfaceInstance(GuideFlow.Publisher.class));
        this._source4guide.create(flow, flow.UNOBTAIN );
        return flow.queryInterfaceInstance(Guide.class);
    }
    
    final public BizStep DISPATCH = new BizStep("httpmediator.DISPATCH")
        .handler(handlersOf(this))
        .freeze();

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
            }
            else {
                if ( LOG.isTraceEnabled() ) {
                    LOG.trace("Pendings: add GuideFlow({}) to pending queue failed", flow);
                }
            }
        }
        
        return cancelPreviousAndMakeNewTimeoutBizStep();
    }

    @OnEvent(event = "notifyPendingGuideSelectChannel")
    private BizStep notifyPendingGuideSelectChannel() {
        final GuideFlow guide = this._pendingGuides.peek();
        if ( LOG.isTraceEnabled() ) {
            LOG.trace("notifyPendingGuideSelectChannel: try notify guide({}) select channel.", guide);
        }
        if ( null != guide ) {
            notifyGuidStartSelecting(guide);
        }
        return cancelPreviousAndMakeNewTimeoutBizStep();
    }
    
    @OnDelayed
    private BizStep onTimeout() {
        return notifyPendingGuideSelectChannel();
    }

    /**
     * @return
     */
    private BizStep cancelPreviousAndMakeNewTimeoutBizStep() {
        removeAndCancelAllDealyEvents(this._timers);
        return ((BizStep)this.fireDelayEventAndAddTo(
                this.DISPATCH.makePredefineDelayEvent(1000), 
                this._timers))
                .freeze();
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
    
    private ChannelFlow createInactiveChannelFlow() {
        final ChannelFlow channelFlow = new ChannelFlow(
                this._channelPublisher,
                this._channelToolkit, 
                this._bytesPool)
            .addFlowLifecycleListener(this._channelFlowLifecycleListener);
        
        this._source4channel.create(channelFlow, channelFlow.INACTIVE );
        return channelFlow;
    }
    
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
        public void publishChannelBinded(final ChannelFlow channelFlow) {
            _bindedChannelCount.incrementAndGet();
        }

        @Override
        public void publishChannelUnbind(final ChannelFlow channelFlow) {
            _bindedChannelCount.decrementAndGet();
            try {
                selfEventReceiver().acceptEvent("notifyPendingGuideSelectChannel");
            } catch (Throwable e) {
                LOG.warn("exception when emit notifyPendingGuidSelectChannel, detail:{}", 
                        ExceptionUtils.exception2detail(e));
            }
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

    private final int _maxChannelCount;
    private final AtomicInteger _currentChannelCount = new AtomicInteger(0);
    private final List<EventReceiver> _channelReceivers = new CopyOnWriteArrayList<EventReceiver>();
    private final AtomicInteger _bindedChannelCount = new AtomicInteger(0);
    
    private final Queue<GuideFlow> _pendingGuides = new PriorityBlockingQueue<GuideFlow>();
    
    private final BytesPool _bytesPool;
    private final NettyClient _client;
    private final EventReceiverSource _source4guide;
    private final EventReceiverSource _source4channel;
    private final List<Detachable> _timers = new ArrayList<Detachable>();
}
