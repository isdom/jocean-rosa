package org.jocean.rosa.impl;

import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.api.DownloadAgent;
import org.jocean.rosa.impl.flow.DownloadFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DownloadAgentImpl implements DownloadAgent {

    private static final Logger LOG =
            LoggerFactory.getLogger(DownloadAgentImpl.class);
    
    @Override
    public DownloadTask createDownloadTask() {
        final DownloadFlow flow = new DownloadFlow(this._pool, this._guideBuilder);
        this._engine.create(flow, flow.WAIT);
        
        return flow.queryInterfaceInstance(DownloadTask.class);
    }
    
    public DownloadAgentImpl(
            final BytesPool pool,
            final GuideBuilder guideBuilder, 
            final EventEngine engine) {
        this._pool = pool;
        this._guideBuilder = guideBuilder;
        this._engine = engine;
    }
    
    @Override
    public void detachHttpClientOf(final DownloadTask task) {
        if ( null != task ) {
            try {
                ((EventReceiver)task).acceptEvent("detachHttpClient");
            } catch (Throwable e) {
                LOG.warn("exception when invoke detachHttpClient for {}, detail: {}", 
                        task, ExceptionUtils.exception2detail(e));
            }
        }
    }
    
    private final BytesPool _pool;
    private final GuideBuilder _guideBuilder;
    private final EventEngine _engine;

}
