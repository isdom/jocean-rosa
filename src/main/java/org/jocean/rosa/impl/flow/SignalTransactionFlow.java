/**
 * 
 */
package org.jocean.rosa.impl.flow;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.ArgsHandler;
import org.jocean.event.api.ArgsHandlerSource;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.ByteArrayListInputStream;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.rosa.api.SignalReactor;
import org.jocean.rosa.api.TransactionConstants;
import org.jocean.rosa.api.TransactionPolicy;
import org.jocean.transportclient.TransportUtils;
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
public class SignalTransactionFlow<RESP> extends AbstractFlow<SignalTransactionFlow<RESP>> 
    implements ArgsHandlerSource {

	private static final Logger LOG = LoggerFactory
			.getLogger("SignalTransactionFlow");

    public SignalTransactionFlow(
            final HttpStack stack, 
            final URI uri, 
            final Class<?> respCls) {
        this._stack = stack;
        this._uri = uri;
        this._respCls = respCls;
        
        addFlowLifecycleListener(new FlowLifecycleListener<SignalTransactionFlow<RESP>>() {

            @Override
            public void afterEventReceiverCreated(
                    SignalTransactionFlow<RESP> flow, EventReceiver receiver)
                    throws Exception {
            }

            @Override
            public void afterFlowDestroy(SignalTransactionFlow<RESP> flow)
                    throws Exception {
                if ( null != _forceFinishedTimer) {
                    _forceFinishedTimer.detach();
                    _forceFinishedTimer = null;
                }
                notifyReactorFinsihed();
            }} );
    }
    
    @Override
    public ArgsHandler getArgsHandler() {
        return TransportUtils.getSafeRetainArgsHandler();
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
            .handler(selfInvoker("onScheduled"))
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
                       _uri, this._retryCount, this._maxRetryCount);
                }
                this.setFinishedStatus(TransactionConstants.FINISHED_RETRY_FAILED);
                return null;
            }
        }
    }

    private BizStep delayRetry() {
        //  delay 1s, and re-try
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("delay {}s and retry fetch signal uri:{}", this._timeoutBeforeRetry / 1000, this._uri);
        }
        this._scheduleTimer = this.selfExectionLoop().schedule(
                this.queryInterfaceInstance(Runnable.class), this._timeoutBeforeRetry);
        tryStartForceFinishedTimer();
        return SCHEDULE;
    }
    
    @OnEvent(event = "run")
    private BizStep onScheduled() {
        startObtainHttpClient();
        return OBTAINING;
    }

    @OnEvent(event="detach")
    private BizStep schedulingOnDetach() throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("fetch response for uri:{} when scheduling and canceled", this._uri);
        }
        this._scheduleTimer.detach();
        safeDetachHttpHandle();
        return null;
    }
    
	@OnEvent(event = "start")
	private BizStep onSignalTransactionStart(
	        final Object request, 
	        final SignalReactor<RESP> reactor, 
	        final TransactionPolicy policy) {
		this._signalReactor = reactor;
		
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
		final HttpRequest request = genHttpRequest(this._uri);
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
                        setFinishedStatus(TransactionConstants.FINISHED_TIMEOUT);
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
			LOG.debug("channel for {} recv response {}", _uri, response);
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
	private BizStep contentReceived(final HttpContent content) {
		TransportUtils.readByteBufToBytesList(content.content(), this._bytesList);
		return RECVCONTENT;
	}

	@SuppressWarnings("unchecked")
    @OnEvent(event = "onLastHttpContentReceived")
	private BizStep lastContentReceived(final LastHttpContent content) throws Exception {
		TransportUtils.readByteBufToBytesList(content.content(), this._bytesList);
		
        safeDetachHttpHandle();
        
		final InputStream is = new ByteArrayListInputStream(_bytesList);
		final byte[] totalbytes = new byte[sizeOf(_bytesList)];
		is.read(totalbytes);
		is.close();
		if ( null != this._signalReactor ) {
			if ( LOG.isDebugEnabled() ) {
				printLongText(new String(totalbytes), 80);
			}
			try {
                this.setFinishedStatus(TransactionConstants.FINISHED_SUCCEED);
				this._signalReactor.onResponseReceived((RESP)JSON.parseObject(totalbytes, this._respCls));
			}
			catch (Exception e) {
				LOG.warn("exception when signalReactor.onResponseReceived for uri:{}, detail:{}", 
						this._uri, ExceptionUtils.exception2detail(e));
			}
		}
		return null;
	}

	private void printLongText(final String text, final int size) {
		int pos = 0;
		while ( pos < text.length() ) {
			final int len = Math.min( text.length() - pos, size );
			LOG.debug( "{}", text.substring(pos, pos + len) );
			pos += size;
		}
	}
	
	private static HttpRequest genHttpRequest(final URI uri) {
		// Prepare the HTTP request.
		final String host = uri.getHost() == null ? "localhost" : uri.getHost();

		final HttpRequest request = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
		request.headers().set(HttpHeaders.Names.HOST, host);
		request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING,
				HttpHeaders.Values.GZIP);
		
		return request;
	}
	
	private static int sizeOf(final List<byte[]> bytesList) {
		int totalSize = 0;
		for ( byte[] bytes : bytesList) {
			totalSize += bytes.length;
		}
		
		return totalSize;
	}
	
	public void notifyReactorFinsihed() {
		if ( null != this._signalReactor ) {
			try {
				this._signalReactor.onTransactionFinished(this._finishedStatus);
			}
			catch (Exception e) {
				LOG.warn("exception when signalReactor.onTransactionFinsihed for uri:{}, detail:{}", 
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
        }
    }
    
    private void setFinishedStatus(final int status) {
        this._finishedStatus = status;
    }
    
    private final HttpStack _stack;
	private final URI _uri;
	private final Class<?> _respCls;
    private int _maxRetryCount = -1;
    private int _retryCount = 0;
    private long   _timeoutFromActived = -1;
    private long   _timeoutBeforeRetry = 1000L;
    private TransactionPolicy _policy = null;
	private final List<byte[]> _bytesList = new ArrayList<byte[]>();
	private SignalReactor<RESP> _signalReactor;
    private HttpClientHandle _handle;
    private Detachable _scheduleTimer;
    private Detachable _forceFinishedTimer;
    private int _finishedStatus = TransactionConstants.FINISHED_UNKNOWN;
}
