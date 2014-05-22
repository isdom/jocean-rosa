/**
 * 
 */
package org.jocean.rosa.impl.flow;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;

import java.io.InputStream;
import java.net.URI;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.ArgsHandler;
import org.jocean.idiom.ArgsHandlerSource;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.block.BlockUtils;
import org.jocean.idiom.block.PooledBytesOutputStream;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.api.BusinessServerAgent.SignalReactor;
import org.jocean.rosa.api.TransactionConstants;
import org.jocean.rosa.api.TransactionPolicy;
import org.jocean.transportclient.api.HttpClient;
import org.jocean.transportclient.api.HttpClientHandle;
import org.jocean.transportclient.api.HttpReactor;
import org.jocean.transportclient.http.HttpStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

/**
 * @author isdom
 *
 */
public class SignalTransactionFlow extends AbstractFlow<SignalTransactionFlow> 
    implements ArgsHandlerSource {
    
    public interface SignalConverter {
        
        public URI req2uri(final Object request);
        
        public HttpRequest processHttpRequest(final Object request, 
                final DefaultFullHttpRequest httpRequest);
    }

	private static final Logger LOG = LoggerFactory
			.getLogger(SignalTransactionFlow.class);

    public SignalTransactionFlow(
            final BytesPool pool,
            final HttpStack stack, 
            final SignalConverter signalConverter) {
        this._bytesStream = new PooledBytesOutputStream(pool);
        this._stack = stack;
        this._converter = signalConverter;
        
        addFlowLifecycleListener(new FlowLifecycleListener<SignalTransactionFlow>() {

            @Override
            public void afterEventReceiverCreated(
                    SignalTransactionFlow flow, EventReceiver receiver)
                    throws Exception {
            }

            @Override
            public void afterFlowDestroy(SignalTransactionFlow flow)
                    throws Exception {
                clearCurrentContent();
                if ( null != SignalTransactionFlow.this._forceFinishedTimer) {
                    SignalTransactionFlow.this._forceFinishedTimer.detach();
                    SignalTransactionFlow.this._forceFinishedTimer = null;
                }
                notifyReactorFailureIfNeeded();
            }} );
    }
    
    @Override
    public ArgsHandler getArgsHandler() {
        return ArgsHandler.Consts._REFCOUNTED_ARGS_GUARD;
    }
    
    private void clearCurrentContent() {
        this._bytesStream.clear();
    }
    
	public final BizStep WAIT = new BizStep("signal.WAIT")
			.handler(selfInvoker("onSignalTransactionStart"))
			.handler(selfInvoker("onDetach"))
			.freeze();
	
	private final BizStep OBTAINING = new BizStep("signal.OBTAINING")
			.handler(selfInvoker("onHttpObtained"))
			.handler(selfInvoker("onHttpLost"))
			.handler(selfInvoker("onDetach"))
			.freeze();

	private final BizStep RECVRESP = new BizStep("signal.RECVRESP")
			.handler(selfInvoker("responseReceived"))
			.handler(selfInvoker("onHttpLost"))
			.handler(selfInvoker("onDetach"))
			.freeze();

	private final BizStep RECVCONTENT = new BizStep("signal.RECVCONTENT")
			.handler(selfInvoker("contentReceived"))
			.handler(selfInvoker("lastContentReceived"))
			.handler(selfInvoker("onHttpLost"))
			.handler(selfInvoker("onDetach"))
			.freeze();

    private final BizStep SCHEDULE = new BizStep("signal.SCHEDULE")
            .handler(selfInvoker("schedulingOnDetach"))
            .freeze();
    
	@OnEvent(event="detach")
	private BizStep onDetach() throws Exception {
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("fetch response for uri:{} progress canceled", this._uri);
		}
		safeDetachHttpHandle();
		return null;
	}
	
	@OnEvent(event = "onHttpClientLost")
	private BizStep onHttpLost()
			throws Exception {
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("http for {} lost.", this._uri);
		}
        return incRetryAndSelectStateByRetry();
	}

    private BizStep incRetryAndSelectStateByRetry() {
        this._retryCount++;
        if ( this._maxRetryCount < 0 ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("uri:{} 's max retry count < 0, so retry forever, now retry count is {}.", 
                   _uri, this._retryCount);
            }
            return  delayRetry();
        }
        else {
            if ( this._retryCount <= this._maxRetryCount ) {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("uri:{} 's retry count is {}, when max retry {}, so retry.", 
                       _uri, this._retryCount, this._maxRetryCount);
                }
                return delayRetry();
            }
            else {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("uri:{} 's retry count is {} reached max retry {}, so image download canceled.",
                       this._uri, this._retryCount, this._maxRetryCount);
                }
                this.setFailureReason(TransactionConstants.FAILURE_RETRY_FAILED);
                return null;
            }
        }
    }

    private BizStep delayRetry() {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("delay {}s and retry fetch signal uri:{}", this._timeoutBeforeRetry / 1000, this._uri);
        }
        
        tryStartForceFinishedTimer();
        return ((BizStep)this.fireDelayEventAndPush(
                this.SCHEDULE.makeDelayEvent(
                    selfInvoker("onScheduled"), 
                    this._timeoutBeforeRetry)))
                .freeze();
    }
    
    @SuppressWarnings("unused")
    private BizStep onScheduled() {
        clearCurrentContent();
        startObtainHttpClient();
        return OBTAINING;
    }

    @OnEvent(event="detach")
    private BizStep schedulingOnDetach() throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("fetch response for uri:{} when scheduling and canceled", this._uri);
        }
        this.popAndCancelDealyEvents();
        safeDetachHttpHandle();
        return null;
    }
    
	@OnEvent(event = "start")
	private BizStep onSignalTransactionStart(
	        final Object request, 
	        final Object ctx,
	        final Class<?> respCls,
	        final SignalReactor<Object, Object> reactor, 
	        final TransactionPolicy policy) {
	    this._request = request;
	    this._ctx = ctx;
		this._signalReactor = reactor;
		this._respCls = respCls;
		this._uri = this._converter.req2uri(request);
		
		if ( null == this._uri ) {
		    // request not registered
		    LOG.error("request ({}) !NOT! registered with a valid URI, so finished signal flow({})", request, this);
		    return null;
		}
		
        if ( null != policy ) {
            this._maxRetryCount = policy.maxRetryCount();
            this._timeoutFromActived = policy.timeoutFromActived();
            this._timeoutBeforeRetry = Math.max( policy.timeoutBeforeRetry(), this._timeoutBeforeRetry);
            this._policy = policy;
        }
		
		startObtainHttpClient();
		return OBTAINING;
	}
	
	@OnEvent(event = "onHttpClientObtained")
	private BizStep onHttpObtained(final HttpClient httpclient) {
		final HttpRequest request = 
		        this._converter.processHttpRequest( this._request, genHttpRequest(this._uri));
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("send http request {}", request);
		}
        try {
            httpclient.sendHttpRequest( request );
        }
        catch (Exception e) {
            LOG.error("state({})/{}: exception when sendHttpRequest, detail:{}", 
                    currentEventHandler().getName(), currentEvent(), ExceptionUtils.exception2detail(e));
        }
		tryStartForceFinishedTimer();
		return RECVRESP;
	}

    private void tryStartForceFinishedTimer() {
        if ( null == this._forceFinishedTimer && this._timeoutFromActived > 0) {
            this._forceFinishedTimer = this.selfExectionLoop().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        if ( LOG.isDebugEnabled() ) {
                            LOG.debug("uri:{} force finished timeout, so force detach.", _uri);
                        }
                        _forceFinishedTimer = null;
                        setFailureReason(TransactionConstants.FAILURE_TIMEOUT);
                        selfEventReceiver().acceptEvent("detach");
                    } catch (Exception e) {
                        LOG.warn("exception when acceptEvent detach by force finished for uri:{}, detail:{}", 
                                _uri, ExceptionUtils.exception2detail(e));
                    }
                }}, this._timeoutFromActived);
        }
    }
    
	@OnEvent(event = "onHttpResponseReceived")
	private BizStep responseReceived(final HttpResponse response) {
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("channel for {} recv response {}", this._uri, response);
		}
		final String contentType = response.headers().get(HttpHeaders.Names.CONTENT_TYPE);
		if ( contentType != null && contentType.startsWith("application/json")) {
			LOG.info("try to get json succeed");
			return RECVCONTENT;
		}
		else {
			LOG.info("get json failed, wrong contentType {}", contentType);
			return	null;
		}
	}

	@OnEvent(event = "onHttpContentReceived")
	private BizStep contentReceived(final Blob contentBlob) {
	    BlockUtils.blob2OutputStream(contentBlob, this._bytesStream);
		return RECVCONTENT;
	}

    @OnEvent(event = "onLastHttpContentReceived")
	private BizStep lastContentReceived(final Blob contentBlob) throws Exception {
        BlockUtils.blob2OutputStream(contentBlob, this._bytesStream);
		
        safeDetachHttpHandle();
		
        final InputStream is = 
                Blob.Utils.releaseAndGenInputStream(this._bytesStream.drainToBlob());
        
        if (null==is) {
            return null;
        }
        
        if ( LOG.isTraceEnabled() ) {
            is.mark(0);
            printLongText(is, 80, is.available());
            is.reset();
        }
        
        final SignalReactor<Object, Object> reactor = this._signalReactor;
        this._signalReactor = null;   // clear _signalReactor 字段，这样 onTransactionFailure 不会再被触发
        
        try {
            if ( null != reactor) {
                boolean feedbackResponse = false;
                // final JSONReader reader = new JSONReader(new InputStreamReader(is, "UTF-8"));
    			try {
    	            final byte[] bytes = new byte[is.available()];
    	            final int readed = is.read(bytes);
    	            final Object resp = JSON.parseObject(bytes,this._respCls);
    	            
    //                final Object resp = reader.readObject(this._respCls);
                    if ( null != resp ) {
                        try {
                            feedbackResponse = true;
    	                    reactor.onResponseReceived(this._ctx, resp);
    	                    if ( LOG.isTraceEnabled() ) {
    	                        LOG.trace("signalTransaction invoke onResponseReceived succeed. uri:({})", this._uri);
    	                    }
                        }
                        catch (Throwable e) {
                            LOG.warn("exception when SgnalReactor.onResponseReceived for uri:{}, detail:{}", 
                                    this._uri, ExceptionUtils.exception2detail(e));
                        }
                    }
    			}
    			catch (Throwable e) {
    				LOG.warn("exception when prepare response for uri:{}, detail:{}", 
    						this._uri, ExceptionUtils.exception2detail(e));
    			}
    			finally {
                    if ( !feedbackResponse ) {
                        // ensure notify onTransactionFailure with FAILURE_NOCONTENT
                        this._signalReactor = reactor;
                        setFailureReason(TransactionConstants.FAILURE_NOCONTENT);
                    }
    			}
    		}
        }
        finally {
            is.close();
        }
        
		return null;
	}

    private void printLongText(final InputStream is, final int size, final int totalSize) {
        try {
            final byte[] bytes = new byte[totalSize];
            is.read(bytes);
            final String text = new String(bytes, "UTF-8");
    		int pos = 0;
    		while ( pos < text.length() ) {
    			final int len = Math.min( text.length() - pos, size );
    			LOG.trace( "{}", text.substring(pos, pos + len) );
    			pos += size;
    		}
        }
        catch (Exception e) {
            LOG.warn("exception when printLongText, detail:{}", 
                    ExceptionUtils.exception2detail(e));
        }
	}
	
	private static DefaultFullHttpRequest genHttpRequest(final URI uri) {
		// Prepare the HTTP request.
		final String host = uri.getHost() == null ? "localhost" : uri.getHost();

		final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
		request.headers().set(HttpHeaders.Names.HOST, host);
		request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING,
				HttpHeaders.Values.GZIP);
		
		return request;
	}
	
	public void notifyReactorFailureIfNeeded() {
		if ( null != this._signalReactor ) {
			try {
				this._signalReactor.onTransactionFailure(this._ctx, this._failureReason);
			}
			catch (Exception e) {
				LOG.warn("exception when SignalReactor.onTransactionFailure for uri:{}, detail:{}", 
						this._uri, ExceptionUtils.exception2detail(e));
			}
		}
	}
	
    private void startObtainHttpClient() {
        this._handle = this._stack.createHttpClientHandle();
        this._handle.obtainHttpClient( 
                new HttpClientHandle.DefaultContext()
                    .uri(this._uri)
                    .priority( null != this._policy ? this._policy.priority() : 0)
                , this.queryInterfaceInstance(HttpReactor.class) );
    }

    private void safeDetachHttpHandle() {
        if ( null != this._handle ) {
            try {
                this._handle.detach();
            }
            catch (Exception e) {
                LOG.warn("exception when detach http handle for uri:{}, detail:{}",
                        this._uri, ExceptionUtils.exception2detail(e));
            }
            this._handle = null;
        }
    }
    
    private void setFailureReason(final int failureReason) {
        this._failureReason = failureReason;
    }
    
    private final HttpStack _stack;
	private final SignalConverter _converter;
	private Object _request;
    private URI _uri;
	private Class<?> _respCls;
    private int _maxRetryCount = -1;
    private int _retryCount = 0;
    private long   _timeoutFromActived = -1;
    private long   _timeoutBeforeRetry = 1000L;
    private TransactionPolicy _policy = null;
    private final PooledBytesOutputStream _bytesStream;
    private Object  _ctx;
	private SignalReactor<Object, Object> _signalReactor;
    private HttpClientHandle _handle;
    private Detachable _forceFinishedTimer;
    private int _failureReason = TransactionConstants.FAILURE_UNKNOWN;
}
