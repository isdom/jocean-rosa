package org.jocean.rosa.impl;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.httpclient.HttpStack;
import org.jocean.rosa.api.DownloadAgent;
import org.jocean.rosa.impl.flow.DownloadFlow;

public class DownloadAgentImpl implements DownloadAgent {

    @Override
    public DownloadTask createDownloadTask() {
        final DownloadFlow flow = new DownloadFlow( this._stack);
        this._source.create(flow, flow.WAIT);
        
        return flow.queryInterfaceInstance(DownloadTask.class);
    }
    
    public DownloadAgentImpl(
            final HttpStack httpStack, 
            final EventReceiverSource source) {
        this._stack = httpStack;
        this._source = source;
    }
    
    private final HttpStack _stack;
    private final EventReceiverSource _source;

}
