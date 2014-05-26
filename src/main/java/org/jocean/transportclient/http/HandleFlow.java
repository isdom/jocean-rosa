/**
 * 
 */
package org.jocean.transportclient.http;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.transportclient.api.HttpClient;
import org.jocean.transportclient.api.HttpClientHandle;
import org.jocean.transportclient.api.HttpReactor;
import org.jocean.transportclient.http.Events.FlowEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
class HandleFlow extends AbstractFlow<HandleFlow> implements Comparable<HandleFlow> {
    
    interface Holder {
        public void addToPendings(final HandleFlow handleFlow);
        public void removeFromPendings(final HandleFlow handleFlow);
    }
    
    private static final Logger LOG = LoggerFactory
            .getLogger("http.HandleFlow");

    HandleFlow(final Holder holder ) {
        this._holder = holder;
    }
    
    final BizStep UNOBTAIN = new BizStep("httphandle.UNOBTAIN")
            .handler(selfInvoker("unobtainOnDetach"))
            .handler(selfInvoker("onObtainClient")).freeze();

    private final BizStep PENDING = new BizStep("httphandle.PENDING")
            .handler(selfInvoker("pendingOnDetach"))
            .handler(selfInvoker("onStartAttachChannel")).freeze();

    private final BizStep ATTACHING = new BizStep("httphandle.ATTACHING")
            .handler(selfInvoker("attachingOnFailed"))
            .handler(selfInvoker("attachingOnDetach"))
            .handler(selfInvoker("attachingOnAttached")).freeze();

    private final BizStep ATTACHED = new BizStep("httphandle.ATTACHING")
            .handler(selfInvoker("attachedOnHttpClientObtained"))
            .handler(selfInvoker("attachedOnDetach"))
            .handler(selfInvoker("channelLost")).freeze();
    
    @OnEvent(event = "detach")
    private BizStep unobtainOnDetach() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("handle(unobtain) has been released.");
        }
        return null;
    }

    @OnEvent(event = "obtainHttpClient")
    private BizStep onObtainClient(final HttpClientHandle.Context ctx, final HttpReactor reactor) {
        if ( null == ctx || null == reactor ) {
            LOG.error("handleFlow({})/{}/{} invalid params, detail: HttpClientHandle.Context:{} HttpReactor:{}", 
                    this, currentEventHandler().getName(), currentEvent(), ctx, reactor);
            throw new NullPointerException("HttpClientHandle.Context and HttpReactor can't be null.");
        }
        this._ctx = new HandleContextImpl<HandleFlow>(ctx, this);
        this._reactor = reactor;
        this._holder.addToPendings(this);
        return PENDING;
    }

    @OnEvent(event = "detach")
    private BizStep pendingOnDetach() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("handleFlow({})/{}/{} has been detached.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        this._holder.removeFromPendings(this);
        notifyHttpLost();
        return null;
    }

    @OnEvent(event = FlowEvents.START_ATTACH)
    private BizStep onStartAttachChannel(final HttpChannelFlow channelFlow) 
            throws Exception {
        if ( LOG.isInfoEnabled() ) {
            LOG.info("handleFlow({})/{}/{} start attaching to channel:{}", 
                    this, currentEventHandler().getName(), currentEvent(), channelFlow );
        }
        
        channelFlow.selfEventReceiver().acceptEvent(
                new AbstractUnhandleAware(FlowEvents.ATTACHING) {
                    @Override
                    public void onEventUnhandle( final String event, final Object... args) 
                            throws Exception {
                        // means channel bind failed
                        selfEventReceiver().acceptEvent(FlowEvents.ATTACHING_FAILED);
                    }},
                this);

        return ATTACHING;
    }

    @OnEvent(event = FlowEvents.ATTACHING_FAILED)
    private BizStep attachingOnFailed() throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("handleFlow({}) bind channelFlow({}) failed. try to re-attach", 
                    this, this._channelReceiver);
        }
        this._holder.addToPendings(this);
        return PENDING;
    }
    
    @OnEvent(event = "detach")
    private BizStep attachingOnDetach() throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("handleFlow({})/{}/{} has been detached.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        return null;
    }

    @OnEvent(event = FlowEvents.ATTACHED)
    private BizStep attachingOnAttached(final HttpChannelFlow channelFlow) throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("handleFlow({})/{}/{} has attached channelFlow({}).", 
                    this, currentEventHandler().getName(), currentEvent(), channelFlow);
        }
        this._channelReceiver = channelFlow.selfEventReceiver();
        return this.ATTACHED;
    }
    
    @OnEvent(event = "detach")
    private BizStep attachedOnDetach() throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("handleFlow({})/{}/{} has been detached.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        this._channelReceiver.acceptEvent("detach", this);
        notifyHttpLost();
        return null;
    }
    
    @OnEvent(event = "onHttpClientObtained")
    private BizStep attachedOnHttpClientObtained(final HttpClient httpClient) throws Exception {
        if (null != this._reactor) {
            try {
                this._reactor.onHttpClientObtained(httpClient);
            } catch (Exception e) {
                LOG.warn("exception when invoke onHttpClientObtained",
                        e);
            }
        } 
        else {
            LOG.warn("connecting with internal error bcs non-receiver");
        }
        return currentEventHandler();
    }
    
    @OnEvent(event = FlowEvents.CHANNELLOST)
    private BizStep channelLost() throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("handleFlow({})/{}/{} channel lost.", 
                    this, currentEventHandler().getName(), currentEvent());
        }
        notifyHttpLost();
        return null;
    }

    private void notifyHttpLost() {
        if (null != this._reactor) {
            try {
                this._reactor.onHttpClientLost();
            } catch (Exception e) {
                LOG.warn("exception when invoke onHttpClientLost", e);
            }
        } else {
            LOG.warn("internal error bcs null reactor");
        }
    }
    
    @Override
    public EventReceiver selfEventReceiver() {
        return super.selfEventReceiver();
    }

    @Override
    public int compareTo(final HandleFlow o) {
        final HttpClientHandle.Context selfCtx = this._ctx;
        final HttpClientHandle.Context otherCtx = o._ctx;
        if ( null == selfCtx) {
            LOG.error("HttpClientHandle.Context not set for HandleFlow({})", this);
            throw new NullPointerException("HttpClientHandle.Context not set");
        }
        if ( null == otherCtx) {
            LOG.error("HttpClientHandle.Context not set for HandleFlow({})", o);
            throw new NullPointerException("HttpClientHandle.Context not set");
        }
        return otherCtx.priority() - selfCtx.priority();
    }
    
    public HttpClientHandle.Context context() {
        return this._ctx;
    }
    
    @Override
    public String toString() {
        return "HandleFlow [ctx=" + _ctx 
                + ", state(" + currentEventHandler().getName()
                + "), reactor(" + 
                (null != _reactor ? "not null" : "null") 
                + ")/channelReceiver(" + 
                (null != _channelReceiver ? "not null" : "null") 
                + ")]";
    }

    private final Holder _holder;
    volatile HttpReactor _reactor;
    private volatile HttpClientHandle.Context _ctx;
    private EventReceiver _channelReceiver;
}
