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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.rosa.DefaultBlob;
import org.jocean.rosa.api.BlobReactor;
import org.jocean.rosa.api.HttpBodyPart;
import org.jocean.rosa.api.HttpBodyPartRepo;
import org.jocean.rosa.api.TransactionConstants;
import org.jocean.rosa.api.TransactionPolicy;
import org.jocean.syncfsm.api.AbstractFlow;
import org.jocean.syncfsm.api.ArgsHandler;
import org.jocean.syncfsm.api.ArgsHandlerSource;
import org.jocean.syncfsm.api.BizStep;
import org.jocean.syncfsm.api.EventHandler;
import org.jocean.syncfsm.api.EventReceiver;
import org.jocean.syncfsm.api.FlowLifecycleListener;
import org.jocean.syncfsm.api.annotation.OnEvent;
import org.jocean.transportclient.TransportUtils;
import org.jocean.transportclient.api.HttpClient;
import org.jocean.transportclient.api.HttpClientHandle;
import org.jocean.transportclient.api.HttpReactor;
import org.jocean.transportclient.http.HttpStack;
import org.jocean.transportclient.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class BlobTransactionFlow extends AbstractFlow<BlobTransactionFlow> 
    implements ArgsHandlerSource {

	private static final Logger LOG = LoggerFactory
			.getLogger("BlobTransactionFlow");

    public BlobTransactionFlow(
            final HttpStack stack, 
            final HttpBodyPartRepo repo) {
        this._stack = stack;
        this._partRepo = repo;
        
        addFlowLifecycleListener(new FlowLifecycleListener<BlobTransactionFlow>() {

            @Override
            public void afterEventReceiverCreated(
                    final BlobTransactionFlow flow, final EventReceiver receiver)
                    throws Exception {
            }

            @Override
            public void afterFlowDestroy(final BlobTransactionFlow flow)
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
    
	public final BizStep WAIT = new BizStep("blob.WAIT")
			.handler(selfInvoker("onTransactionStart"))
			.handler(selfInvoker("onDetach"))
			.freeze();
	
	private final BizStep OBTAINING = new BizStep("blob.OBTAINING")
			.handler(selfInvoker("onHttpObtained"))
			.handler(selfInvoker("onHttpLost"))
			.handler(selfInvoker("onDetach"))
			.freeze();

	private final BizStep RECVRESP = new BizStep("blob.RECVRESP")
			.handler(selfInvoker("responseReceived"))
			.handler(selfInvoker("onHttpLost"))
			.handler(selfInvoker("onDetach"))
			.freeze();
	
	private final BizStep RECVCONTENT = new BizStep("blob.RECVCONTENT")
			.handler(selfInvoker("contentReceived"))
			.handler(selfInvoker("lastContentReceived"))
			.handler(selfInvoker("onDetachAndSaveUncompleteContent"))
			.handler(selfInvoker("onHttpLostAndSaveUncompleteContent"))
			.freeze();

    private final BizStep SCHEDULE = new BizStep("blob.SCHEDULE")
            .handler(selfInvoker("onScheduled"))
            .handler(selfInvoker("schedulingOnDetach"))
            .freeze();
    
	@OnEvent(event="detach")
	private EventHandler onDetach() throws Exception {
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("download blob {} canceled", _uri);
		}
		safeDetachHttpHandle();
		return null;
	}

	@OnEvent(event = "onHttpClientLost")
	private EventHandler onHttpLost()
			throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("http for {} lost.", this._uri);
        }
		notifyReactorTransportInactived();
		return incRetryAndSelectStateByRetry();
	}

	private EventHandler incRetryAndSelectStateByRetry() {
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
                    LOG.debug("uri:{} 's retry count is {} reached max retry {}, so blob download canceled.",
                       _uri, this._retryCount, this._maxRetryCount);
                }
                this.setFinishedStatus(TransactionConstants.FINISHED_RETRY_FAILED);
                return null;
            }
        }
    }

    private EventHandler delayRetry() {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("delay {}s and retry fetch blob uri:{}", this._timeoutBeforeRetry / 1000, this._uri);
        }
        this._scheduleTimer = this.selfExectionLoop().schedule(
                this.getInterfaceAdapter(Runnable.class), this._timeoutBeforeRetry);
        tryStartForceFinishedTimer();
        return SCHEDULE;
    }
    
    @OnEvent(event = "run")
    private EventHandler onScheduled() {
        clearCurrentContent();
        updatePartAndStartObtainHttpClient();
        return OBTAINING;
    }

    @OnEvent(event="detach")
    private EventHandler schedulingOnDetach() throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("download blob {} when scheduling and canceled", this._uri);
        }
        this._scheduleTimer.detach();
        safeDetachHttpHandle();
        return null;
    }
    
    
    /**
     * 
     */
    private void clearCurrentContent() {
        this._bytesList.clear();
    }

    @OnEvent(event = "start")
	private EventHandler onTransactionStart(
	        final URI uri,
	        final BlobReactor reactor, 
	        final TransactionPolicy policy) {
        this._uri = uri;
        this._blobReactor = reactor;
        
        if ( null != policy ) {
            this._maxRetryCount = policy.maxRetryCount();
            this._timeoutFromActived = policy.timeoutFromActived();
            this._timeoutBeforeRetry = Math.max( policy.timeoutBeforeRetry(), this._timeoutBeforeRetry);
            this._policy = policy;
        }
        
        updatePartAndStartObtainHttpClient();
		return OBTAINING;
	}

	@OnEvent(event = "onHttpClientObtained")
	private EventHandler onHttpObtained(final HttpClient httpclient) {
		if ( null != this._blobReactor ) {
			try {
				this._blobReactor.onTransportActived();
			}
			catch (Exception e) {
				LOG.warn("exception when BlobReactor.onTransportActived for uri:{}, detail:{}", 
						this._uri, ExceptionUtils.exception2detail(e));
			}
		}
		final HttpRequest request = genHttpRequest(this._uri, this._part);
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
	private EventHandler responseReceived(final HttpResponse response) {
		this._response = response;
		this._totalLength = HttpHeaders.getContentLength(response, -1);
		this._currentPos = 0;
		
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("channel for {} recv response {}", this._uri, response);
		}
		
		notifyContentType(response.headers().get(HttpHeaders.Names.CONTENT_TYPE));
		
		if ( null != this._part ) {
			// check if content range
			final String contentRange = response.headers().get(HttpHeaders.Names.CONTENT_RANGE);
			if ( null != contentRange ) {
				// assume Partial
				this._bytesList.addAll(this._part.parts());
				LOG.info("uri {}, recv partial get response, detail: {}", this._uri, contentRange);
				
				// 考虑 Content-Range 的情况
				LOG.info("found Content-Range header, parse {}", contentRange);
				final String partialBegin = HttpUtils.getPartialBeginFromContentRange(contentRange);
				if ( null != partialBegin) {
					this._currentPos = Long.parseLong(partialBegin);
				}
				final String partialTotal = HttpUtils.getPartialTotalFromContentRange(contentRange);
				if ( null != partialTotal) {
					this._totalLength = Long.parseLong(partialTotal);
				}
			}
		}
		
		if ( LOG.isInfoEnabled() ) {
		    LOG.info("uri {}, begin download from {} and total size {}", this._uri, this._currentPos, this._totalLength);
		}
		notifyCurrentProgress();
		
		if ( HttpUtils.isHttpResponseHasMoreContent(response) ) {
	        return RECVCONTENT;
		}
		else {
		    LOG.warn("uri:{} has no content, so end fetching blob", this._uri);
		    this.setFinishedStatus(TransactionConstants.FINISHED_NOCONTENT);
	        safeDetachHttpHandle();
		    return null;
		}
	}

	private void notifyContentType(final String contentType) {
        if ( null != this._blobReactor ) {
            try {
                this._blobReactor.onContentTypeReceived(contentType);
            }
            catch (Exception e) {
                LOG.warn("exception when BlobReactor.onContentTypeReceived for uri:{} contentType:{}, detail:{}", 
                        this._uri, contentType, ExceptionUtils.exception2detail(e));
            }
        }
    }

    @OnEvent(event = "onHttpContentReceived")
	private EventHandler contentReceived(final HttpContent content) {
		updateAndNotifyCurrentProgress(
			TransportUtils.readByteBufToBytesList(content.content(), this._bytesList));
		return RECVCONTENT;
	}

	@OnEvent(event = "onLastHttpContentReceived")
	private EventHandler lastContentReceived(final LastHttpContent content) throws Exception {
		updateAndNotifyCurrentProgress(
			TransportUtils.readByteBufToBytesList(content.content(), this._bytesList));
		
        safeDetachHttpHandle();

        if ( null != this._partRepo ) {
            try {
                this._partRepo.remove(this._uri);
            }
            catch (Exception e) {
                LOG.warn("exception when _partRepo.remove for uri:{}, detail:{}",
                        this._uri, ExceptionUtils.exception2detail(e));
            }
        }
        
        this.setFinishedStatus(TransactionConstants.FINISHED_SUCCEED);
        
        if ( null != this._blobReactor ) {
            try {
                this._blobReactor.onBlobReceived(new DefaultBlob( this._bytesList ));
            }
            catch (Exception e) {
                LOG.warn("exception when BlobReactor.onBlobReceived for uri:{}, detail:{}", 
                        this._uri, ExceptionUtils.exception2detail(e));
            }
        }
        
		return null;
	}

	@OnEvent(event="detach")
	private EventHandler onDetachAndSaveUncompleteContent() throws Exception {
		saveHttpBodyPart();
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("download {} progress canceled", _uri);
		}
        safeDetachHttpHandle();
		return null;
	}

	@OnEvent(event = "onHttpClientLost")
	private EventHandler onHttpLostAndSaveUncompleteContent() throws Exception {
		saveHttpBodyPart();
		notifyReactorTransportInactived();
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("channel for {} closed.", _uri);
		}
		return incRetryAndSelectStateByRetry();
	}
	
	private void updateAndNotifyCurrentProgress(long bytesAdded) {
		if ( bytesAdded > 0 ) {
			this._currentPos += bytesAdded;
			notifyCurrentProgress();
		}
	}
	
	private void notifyCurrentProgress() {
		if ( null != this._blobReactor ) {
			try {
				this._blobReactor.onProgress(this._currentPos, this._totalLength);
			}
			catch (Exception e) {
				LOG.warn("exception when imageReactor.onProgress for uri:{} progress{}/{}, detail:{}", 
						this._uri, this._currentPos, this._totalLength, ExceptionUtils.exception2detail(e));
			}
		}
	}

	private void saveHttpBodyPart() {
		if ( null != this._partRepo) {
			try {
                this._partRepo.put(this._uri, new HttpBodyPart(this._response, new DefaultBlob(this._bytesList)));
            } catch (Exception e) {
                LOG.warn("exception when _partRepo.put for uri:{}, detail:{}", 
                        this._uri, ExceptionUtils.exception2detail(e));
            }
		}
	}
	
	private static HttpRequest genHttpRequest(final URI uri, final HttpBodyPart part) {
		// Prepare the HTTP request.
		final String host = uri.getHost() == null ? "localhost" : uri.getHost();

		final HttpRequest request = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
		request.headers().set(HttpHeaders.Names.HOST, host);
		request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING,
				HttpHeaders.Values.GZIP);
		
		if ( null != part ) {
			//	add Range info
			request.headers().set(HttpHeaders.Names.RANGE, "bytes=" + part.blob().length() + "-");
			final String etag = HttpHeaders.getHeader(part.httpResponse(), HttpHeaders.Names.ETAG);
			if ( null != etag ) {
				request.headers().set(HttpHeaders.Names.IF_RANGE, etag);
			}
			LOG.info("uri {}, send partial get request, detail: Range:{}/If-Range:{}", uri, 
					request.headers().get(HttpHeaders.Names.RANGE), 
					request.headers().get(HttpHeaders.Names.IF_RANGE));
		}
		
		return request;
	}
	
	private void notifyReactorFinsihed() {
		if ( null != this._blobReactor ) {
			try {
				this._blobReactor.onTransactionFinished(this._finishedStatus);
			}
			catch (Exception e) {
				LOG.warn("exception when imageReactor.onTransactionFinsihed for uri:{}, detail:{}", 
						this._uri, ExceptionUtils.exception2detail(e));
			}
		}
	}
	
	/**
	 * 
	 */
	private void notifyReactorTransportInactived() {
		if ( null != this._blobReactor ) {
			try {
				this._blobReactor.onTransportInactived();
			}
			catch (Exception e) {
				LOG.warn("exception when imageReactor.onTransportInactived for uri:{}, detail:{}", 
						this._uri, ExceptionUtils.exception2detail(e));
			}
		}
	}

    private void updatePartAndStartObtainHttpClient() {
        if ( null != this._partRepo ) {
            try {
                this._part = this._partRepo.get(this._uri);
            } catch (Exception e) {
                LOG.warn("exception when _partRepo.get for uri:{}, detail:{}", 
                        this._uri, ExceptionUtils.exception2detail(e));
                this._part = null;
            }
        }
        else {
            this._part = null;
        }
        this._handle = this._stack.createHttpClientHandle();
        this._handle.obtainHttpClient( 
                new HttpClientHandle.DefaultContext()
                    .uri(this._uri)
                    .priority( null != this._policy ? this._policy.priority() : 0)
                    .interruptLowPriority( null != this._policy ? this._policy.interruptLowPriority() : false)
                , this.getInterfaceAdapter(HttpReactor.class) );
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
    
    private URI _uri;
    private final HttpBodyPartRepo _partRepo;
    private final HttpStack _stack;
	private int    _maxRetryCount = -1;
	private int    _retryCount = 0;
    private long   _timeoutFromActived = -1;
    private long   _timeoutBeforeRetry = 1000L;
    private TransactionPolicy _policy = null;
	private HttpClientHandle _handle;
	private HttpBodyPart _part;
	private HttpResponse _response;
	private long _totalLength = -1;
	private long _currentPos = -1;
	private Detachable _scheduleTimer;
    private Detachable _forceFinishedTimer;
    private int _finishedStatus = TransactionConstants.FINISHED_UNKNOWN;
	
	private final List<byte[]> _bytesList = new ArrayList<byte[]>();
	private BlobReactor _blobReactor;
}
