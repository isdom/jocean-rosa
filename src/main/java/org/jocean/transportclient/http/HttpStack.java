/**
 * 
 */
package org.jocean.transportclient.http;

import io.netty.channel.Channel;

import java.net.URI;
import java.util.Collection;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.transportclient.TransportClient;
import org.jocean.transportclient.api.HttpClientHandle;
import org.jocean.transportclient.http.Events.FlowEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class HttpStack {

	private static final Logger LOG =
			LoggerFactory.getLogger("transportclient.HttpStack");

    public HttpStack(
            final BytesPool bytesPool,
            final EventReceiverSource source,
            final TransportClient client, 
            final int maxActived) {
        this._bytesPool = bytesPool;
        this._source = source;
        this._client = client;
        this._maxActivedHttpCount = maxActived;
        this._currentTotalHttpCount = 0;
    }

    // invoke within any thread
    public HttpClientHandle createHttpClientHandle() {
        final HandleFlow flow = new HandleFlow(this._handleFlowHolder);
        this._source.create(flow, flow.UNOBTAIN );
        return flow.queryInterfaceInstance(HttpClientHandle.class);
    }

    private void doCheckPendings() {
        if ( LOG.isInfoEnabled() ) {
            LOG.info("doCheckPendings when _pendingHandles's size:({})", _pendingHandles.size());
        }
        int succeedCount = 0;
        int failedCount = 0;
        int skipedCount = 0;
        
        while ( !this._pendingHandles.isEmpty() ) {
            final HandleFlow handle = this._pendingHandles.poll();
            if ( null != handle ) {
                if ( obtainHttpChannelForHandle(handle) ) {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("obtain HttpChannel: for HandleFlow({}) succeed.", handle);
                    }
                    succeedCount++;
                }
                else {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("obtain HttpChannel: for HandleFlow({}) failed.", handle);
                    }
                    skipedCount = this._pendingHandles.size();
                    this._pendingHandles.add(handle);
                    failedCount++;
                    break;
                }
            }
        }
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("doCheckPendings result, succeed:{}/failed:{}/skipped:{}", 
                    succeedCount, failedCount, skipedCount);
        }
    }

    private boolean obtainHttpChannelForHandle(final HandleFlow handleFlow) {
        if ( LOG.isInfoEnabled()) {
            LOG.info("try to launch pending HandleFlow({})", handleFlow);
        }
        
        final HttpChannelFlow channelFlow = findAndReserveChannelFlow(handleFlow);
        if ( null != channelFlow ) {
            attachChannelToPendingHandle(channelFlow, handleFlow);
            return true;
        }
        else {
            return false;
        }
    }
    
    private HttpChannelFlow findAndReserveChannelFlow(final HandleFlow handleFlow) {
        //  step 1: check if idle channel match uri
        //  step 2: check weather can create new channel
        //  step 3: check if can close idle channel (connect other uri)
        //  step 4: check if can interrupt(close) low priority channel
        
        HttpChannelFlow channelFlow = null;
        
        // step 1
        channelFlow = findAndReserveIdleChannelMatch(handleFlow);
        if (null != channelFlow) {
            if ( LOG.isInfoEnabled() ) {
                LOG.info("found idle channel flow {}", channelFlow);
            }
            return  channelFlow;
        }
        
        //  step 2
        channelFlow = findAndReserveInactiveChannel(handleFlow);
        if (null != channelFlow) {
            if ( LOG.isInfoEnabled() ) {
                LOG.info("found inactive channel flow {}", channelFlow);
            }
            return  channelFlow;
        } 
        
        // step 3
        channelFlow = findAndReserveAnyIdleChannel(handleFlow);
        if ( null != channelFlow ) {
            if ( LOG.isInfoEnabled() ) {
                LOG.info("found idle but uri mismatch channel flow {}, try to close and attach", channelFlow);
            }
            return  channelFlow;
        }
        
        if ( canInterruptLowPriority(handleFlow.context().priority()) ) {
            // step 4: check if interrupt (close) low priority channel
            channelFlow = findAndReserveBindedLowPriorityChannel(handleFlow);
            if ( null != channelFlow ) {
                if ( LOG.isInfoEnabled() ) {
                    LOG.info("found binded channel flow({}) try to close and attach", channelFlow);
                }
                return  channelFlow;
            }
        }
        
        return  null;
    }

    /**
     * @param channelReceiver
     * @param handleReceiver
     */
    private void attachChannelToPendingHandle(
            final HttpChannelFlow channelFlow,
            final HandleFlow handleFlow) {
        try {
            final EventReceiver handleReceiver = handleFlow.selfEventReceiver();
            final EventReceiver channelReceiver = channelFlow.selfEventReceiver();
            
            // remember its async event handle or !NOT! 
            handleReceiver.acceptEvent(
                    new AbstractUnhandleAware(FlowEvents.START_ATTACH) {
                        @Override
                        public void onEventUnhandle( final String event, final Object... args) 
                                throws Exception {
                            // if attch channel failed, notify channelFlow
                            channelReceiver.acceptEvent(FlowEvents.START_ATTACH_FAILED);
                        }},
                        channelFlow);
        } catch (Exception e) {
            LOG.warn("exception when FlowEvents.START_ATTACH", e);
        }
    }

    private HttpChannelFlow findAndReserveIdleChannelMatch(final HandleFlow handleFlow) {
        final Set<HttpChannelFlow> pool = getIdleChannelPool( genDomainByURI(handleFlow.context().uri()) );
        if ( null != pool && !pool.isEmpty()) {
            final HttpChannelFlow ret = pool.iterator().next();
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("findAndReserveChannel: found Idle HttpChannelFlow({}) for handleFlow({})", 
                        ret, handleFlow);
            }
            removeFromIdleHttps(ret.bindedDomain(), ret);
            return ret;
        } 
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("findAndReserveChannel: can't find Idle HttpChannelFlow for handleFlow({})", 
                        handleFlow);
            }
            return null;
        }
    }

    private HttpChannelFlow findAndReserveAnyIdleChannel(final HandleFlow handleFlow) {
        final Collection<Set<HttpChannelFlow>> values = this._idleChannels.values();
        for ( Set<HttpChannelFlow> pool : values ) {
            if ( !pool.isEmpty() ) {
                final HttpChannelFlow ret = pool.iterator().next();
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("findAndReserveChannel: found ANY Idle HttpChannelFlow({}) for handleFlow({})", 
                            ret, handleFlow);
                }
                removeFromIdleHttps(ret.bindedDomain(), ret);
                return ret;
            }
        }
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("findAndReserveChannel: can't find ANY Idle HttpChannelFlow for handleFlow({})", 
                    handleFlow);
        }
        return null;
    }
    
    private HttpChannelFlow findAndReserveBindedLowPriorityChannel(final HandleFlow handleFlow) {
        final HandleContextImpl<HttpChannelFlow> channelCtx = this._bindedChannelCtxs.peek();
        if ( null != channelCtx ) {
            if ( canbeInterruptByHighPriority(channelCtx.priority(), handleFlow.context().priority()) ) {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("findAndReserveChannel: found LOW priority HttpChannelFlow({}) for HIGH priority handleFlow({}), try to interrupt it's transaction", 
                            channelCtx.owner(), handleFlow);
                }
                removeFromBindedHttps(channelCtx.owner());
                return  channelCtx.owner();
            }
            else {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("findAndReserveChannel: current lowest priority HttpChannelFlow is ({}), >= handleFlow({})'s priority, just pending",
                            channelCtx.owner(), handleFlow);
                }
            }
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("findAndReserveChannel: can't find any binded HttpChannelFlow for handleFlow({})'s priority, just pending",
                        handleFlow);
            }
        }
        return null;
    }

    private HttpChannelFlow findAndReserveInactiveChannel(final HandleFlow handleFlow) {
        if ( !this._inactiveChannels.isEmpty() ) {
            final HttpChannelFlow ret = this._inactiveChannels.iterator().next();
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("findAndReserveChannel: found inactive HttpChannelFlow({}) for handleFlow({})", 
                        ret, handleFlow);
            }
            removeFromInactiveHttps(ret);
            return ret;
        }
        else if (!isExceedTotalChannelLimit()) {
            final HttpChannelFlow ret = createInactiveChannelFlow();
            if ( LOG.isInfoEnabled() ) {
                LOG.info("findAndReserveChannel: create new HttpChannelFlow({}) for handleFlow({})", 
                        ret, handleFlow);
            }
            return ret;
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("findAndReserveChannel: can't find or create HttpChannelFlow for handleFlow({})", 
                        handleFlow);
            }
            return null;
        }
    }

    private HttpChannelFlow createInactiveChannelFlow() {
        final HttpChannelFlow channelFlow = new HttpChannelFlow(this._channelFlowHolder, this._bytesPool)
            .addFlowLifecycleListener(_channelFlowLifecycleListener);
        
        this._source.create(channelFlow, channelFlow.INACTIVE );
        return channelFlow;
    }

    private boolean isExceedTotalChannelLimit() {
        return (this._currentTotalHttpCount >= this._maxActivedHttpCount);
    }

    private void addToPendings(final HandleFlow flow) {
        if ( this._pendingHandles.add(flow) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("Pendings: add HandleFlow({}) to pending queue succeed", flow);
            }
            startToCheckPendings();
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("Pendings: add HandleFlow({}) to pending queue failed", flow);
            }
        }
    }

    private void removeFromPendings(final HandleFlow flow) {
        if ( this._pendingHandles.remove(flow) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("Pendings: remove HandleFlow({}) from pending queue succeed", flow);
            }
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("Pendings: remove HandleFlow({}) from pending queue failed", flow);
            }
        }
    }

    private void addToIdleHttps(final URI domain, final HttpChannelFlow channelFlow) {
        if ( getOrCreateIdleChannelPool(domain).add(channelFlow) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: add HttpChannelFlow({}) to idle set succeed", channelFlow);
            }
            startToCheckPendings();
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: add HttpChannelFlow({}) to idle set succeed", channelFlow);
            }
        }
    }

    private void removeFromIdleHttps(final URI domain, final HttpChannelFlow channelFlow) {
        final Set<HttpChannelFlow> pool = getIdleChannelPool(domain);
        if ( null != pool ) {
            if ( pool.remove(channelFlow) ) {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("HttpChannels: remove HttpChannelFlow({}) from idle set succeed", channelFlow);
                }
                return;
            }
        }
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("HttpChannels: remove HttpChannelFlow({}) from idle set failed", channelFlow);
        }
    }

    private void addToBindedHttps(final HttpChannelFlow channelFlow) {
        if ( this._bindedChannelCtxs.add(channelFlow.bindedContext()) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: add HttpChannelFlow({}) to binded queue succeed", channelFlow);
            }
            startToCheckPendings();
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: add HttpChannelFlow({}) to binded queue failed", channelFlow);
            }
        }
    }

    private void removeFromBindedHttps(final HttpChannelFlow channelFlow) {
        if ( this._bindedChannelCtxs.remove(channelFlow.bindedContext() ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: remove HttpChannelFlow({}) from binded queue succeed", channelFlow);
            }
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: remove HttpChannelFlow({}) from binded queue failed", channelFlow);
            }
        }
    }
    
    private void addToInactiveHttps(final HttpChannelFlow channelFlow) {
        if ( this._inactiveChannels.add(channelFlow) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: add HttpChannelFlow({}) to inactive set succeed", channelFlow);
            }
            startToCheckPendings();
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: add HttpChannelFlow({}) to inactive set failed", channelFlow);
            }
        }
    }

    private void removeFromInactiveHttps(final HttpChannelFlow channelFlow) {
        if ( this._inactiveChannels.remove(channelFlow) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: remove HttpChannelFlow({}) from inactive set succeed", channelFlow);
            }
        }
        else {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("HttpChannels: remove HttpChannelFlow({}) from inactive set failed", channelFlow);
            }
        }
    }
    
    private void incTotalChannelFlowCount() {
        this._currentTotalHttpCount++;
    }

    private void decTotalChannelCount() {
        this._currentTotalHttpCount--;
    }

    private static boolean canInterruptLowPriority(final int highPriority) {
        return (highPriority >= 0);
    }

    private static boolean canbeInterruptByHighPriority(final int lowPriority, final int highPriority) {
        return ( lowPriority < 0 && highPriority >= 0);
    }

    private void startToCheckPendings() {
        if ( this._needCheckPendings.compareAndSet(false, true) ) {
            this._client.eventLoop().submit(new Runnable() {
                @Override
                public void run() {
                    if ( _needCheckPendings.compareAndSet(true, false) ) {
                        doCheckPendings();
                    }
                }
            });
        }
    }

    private Set<HttpChannelFlow> getOrCreateIdleChannelPool(final URI domain) {
        Set<HttpChannelFlow> pool = this._idleChannels.get(domain);
        if ( null == pool ) {
            pool = new ConcurrentSkipListSet<HttpChannelFlow>();
            final Set<HttpChannelFlow> oldPool = this._idleChannels.putIfAbsent(domain, pool);
            if ( null != oldPool ) {
                pool = oldPool;
            }
        }
        
        return  pool;
    }
    
    private Set<HttpChannelFlow> getIdleChannelPool(final URI domain) {
        return this._idleChannels.get(domain);
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
    
    private final HandleFlow.Holder _handleFlowHolder = new HandleFlow.Holder() {
        public void addToPendings(final HandleFlow handleFlow) {
            HttpStack.this.addToPendings(handleFlow);
        }
        
        public void removeFromPendings(final HandleFlow handleFlow) {
            HttpStack.this.removeFromPendings(handleFlow);
        }
    };
    
    private final HttpChannelFlow.Holder _channelFlowHolder = new HttpChannelFlow.Holder() {
        public void addToIdleHttps(final URI domain, final HttpChannelFlow channelFlow) {
            HttpStack.this.addToIdleHttps(domain, channelFlow);
        }
        
        public void removeFromIdleHttps(final URI domain, final HttpChannelFlow channelFlow) {
            HttpStack.this.removeFromIdleHttps(domain, channelFlow);
        }

        public void addToBindedHttps(final HttpChannelFlow channelFlow) {
            HttpStack.this.addToBindedHttps(channelFlow);
        }
        
        public void removeFromBindedHttps(final HttpChannelFlow channelFlow) {
            HttpStack.this.removeFromBindedHttps(channelFlow);
        }
        
        public void addToInactiveHttps(final HttpChannelFlow channelFlow) {
            HttpStack.this.addToInactiveHttps(channelFlow);
        }
        
        public void removeFromInactiveHttps(final HttpChannelFlow channelFlow) {
            HttpStack.this.removeFromInactiveHttps(channelFlow);
        }
        
        public URI genDomainByURI(final URI uri) {
            return HttpStack.genDomainByURI(uri);
        }
        
        public Channel newChannel() {
            return HttpStack.this._client.newChannel();
        }
    };
    
    private final FlowLifecycleListener<HttpChannelFlow> _channelFlowLifecycleListener = 
            new FlowLifecycleListener<HttpChannelFlow>() {
                @Override
                public void afterEventReceiverCreated(final HttpChannelFlow flow,
                        EventReceiver receiver) throws Exception {
                    incTotalChannelFlowCount();
                }
        
                @Override
                public void afterFlowDestroy(final HttpChannelFlow flow)
                        throws Exception {
                    decTotalChannelCount();
                }
            };
    
    private final AtomicBoolean _needCheckPendings = new AtomicBoolean(false);
    
    private final int _maxActivedHttpCount;
    private int _currentTotalHttpCount = 0;
    
    private final Queue<HandleFlow> _pendingHandles = new PriorityBlockingQueue<HandleFlow>();
    
    //  connected but not binded channels
    private final ConcurrentMap<URI, Set<HttpChannelFlow>> _idleChannels = new ConcurrentHashMap<URI, Set<HttpChannelFlow>>();
    
    //  connected or connecting and binded channels
    private final Queue<HandleContextImpl<HttpChannelFlow>> _bindedChannelCtxs = 
            new PriorityBlockingQueue<HandleContextImpl<HttpChannelFlow>>(11, HttpClientHandle.ASC_COMPARATOR);
    
    //  inactive channels (closed)
    private final Set<HttpChannelFlow> _inactiveChannels = new ConcurrentSkipListSet<HttpChannelFlow>();

    private final BytesPool _bytesPool;
    private final TransportClient _client;
    private final EventReceiverSource _source;
	
}
