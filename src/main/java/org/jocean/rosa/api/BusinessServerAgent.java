/**
 * 
 */
package org.jocean.rosa.api;

import io.netty.handler.codec.http.HttpRequest;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 * 
 */
public interface BusinessServerAgent {

    public interface SignalReactor<CTX, RESPONSE> {

        /**
         * special response received and decode succeed
         * @param response
         * @throws Exception
         */
        public void onResponseReceived(final CTX ctx, final RESPONSE response)
                throws Exception;

        /**
         * signal transaction failed(timeout | decode failed)
         * @throws Exception
         */
        public void onTransactionFailure(final CTX ctx, final int failureReason)
                throws Exception;
    }

    public interface SignalTask extends Detachable {

        public <REQUEST, CTX, RESPONSE> void start(
                final REQUEST request,
                final CTX ctx,
                final Class<RESPONSE> respCls,
                final SignalReactor<CTX, RESPONSE> reactor,
                final TransactionPolicy policy);
    }

    public SignalTask createSignalTask();
    
    
    public interface HttpRequestProcessor {

        public <REQUEST, CTX> void beforeHttpRequestSend(
                final REQUEST request, final CTX ctx, final HttpRequest httpRequest)
                throws Exception;
    }
    
    public SignalTask createSignalTask(final HttpRequestProcessor processor);
}
