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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.ArgsHandler;
import org.jocean.event.api.ArgsHandlerSource;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.idiom.Blob;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.pool.ByteArrayPool;
import org.jocean.idiom.pool.PoolUtils;
import org.jocean.idiom.pool.PooledBytesOutputStream;
import org.jocean.rosa.api.BlobReactor;
import org.jocean.rosa.api.HttpBodyPart;
import org.jocean.rosa.api.HttpBodyPartRepo;
import org.jocean.rosa.api.TransactionConstants;
import org.jocean.rosa.api.TransactionPolicy;
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
            final ByteArrayPool pool,
            final HttpStack stack, 
            final HttpBodyPartRepo repo) {
        this._bytesStream = new PooledBytesOutputStream(pool);
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
                clearCurrentContent();
                safeReleaseBodyPart();
                
                if ( null != BlobTransactionFlow.this._forceFinishedTimer) {
                    BlobTransactionFlow.this._forceFinishedTimer.detach();
                    BlobTransactionFlow.this._forceFinishedTimer = null;
                }
                notifyReactorFailureIfNeeded();
            }} );
    }

    @Override
    public ArgsHandler getArgsHandler() {
        return TransportUtils.guardReferenceCountedArgsHandler();
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
            .handler(selfInvoker("schedulingOnDetach"))
            .freeze();
    
	@OnEvent(event="detach")
	private BizStep onDetach() throws Exception {
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("download blob {} canceled", _uri);
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
		notifyReactorTransportInactived();
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
                    LOG.debug("uri:{} 's retry count is {} reached max retry {}, so blob download canceled.",
                       _uri, this._retryCount, this._maxRetryCount);
                }
                this.setFailureReason(TransactionConstants.FAILURE_RETRY_FAILED);
                return null;
            }
        }
    }

    private BizStep delayRetry() {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("delay {}s and retry fetch blob uri:{}", this._timeoutBeforeRetry / 1000, this._uri);
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
        updatePartAndStartObtainHttpClient();
        return OBTAINING;
    }

    @OnEvent(event="detach")
    private BizStep schedulingOnDetach() throws Exception {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("download blob {} when scheduling and canceled", this._uri);
        }
        this.popAndCancelDealyEvents();
        safeDetachHttpHandle();
        return null;
    }
    
    
    private void clearCurrentContent() {
        this._bytesStream.clear();
    }

    @OnEvent(event = "start")
	private BizStep onTransactionStart(
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
	private BizStep onHttpObtained(final HttpClient httpclient) {
		if ( null != this._blobReactor ) {
			try {
				this._blobReactor.onTransportActived();
			}
			catch (Exception e) {
				LOG.warn("exception when BlobReactor.onTransportActived for uri:{}, detail:{}", 
						this._uri, ExceptionUtils.exception2detail(e));
			}
		}
		final HttpRequest request = genHttpRequest(this._uri, this._bodyPart);
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
		this._response = response;
		this._totalLength = HttpHeaders.getContentLength(response, -1);
		this._currentPos = 0;
		
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("channel for {} recv response {}", this._uri, response);
		}
		
		if ( !response.getStatus().equals(HttpResponseStatus.OK)
		    && !response.getStatus().equals(HttpResponseStatus.PARTIAL_CONTENT)) {
		    
            safeDetachHttpHandle();
            safeRemovePartFromRepo();
		    if ( response.getStatus().equals(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE)) {
		        // 416 Requested Range Not Satisfiable
		        // 清除 part 后，再次尝试完整获取 url
		        // 此前的 httpClientHandle 已经 detach
		        // so 如下直接开始重新获取
    	        clearCurrentContent();
    	        updatePartAndStartObtainHttpClient();
    	        return OBTAINING;
		    }
		    else {
                this.setFailureReason(TransactionConstants.FAILURE_NOCONTENT);
                return null;
		    }
		}
		
		notifyContentType(response.headers().get(HttpHeaders.Names.CONTENT_TYPE));
		
		if ( null != this._bodyPart ) {
			// check if content range
			final String contentRange = response.headers().get(HttpHeaders.Names.CONTENT_RANGE);
			if ( null != contentRange ) {
				// assume Partial
			    final InputStream is = this._bodyPart.blob().genInputStream();
			    
			    try {
			        PoolUtils.inputStream2OutputStream(is, this._bytesStream);
			    }
			    catch (Exception e) {
			        LOG.warn("exception when inputStream2OutputStream, derail:{}", 
			                ExceptionUtils.exception2detail(e));
			    }
			    finally {
			        try {
                        is.close();
                    } catch (IOException e) {
                    }
			    }
				
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
		    this.setFailureReason(TransactionConstants.FAILURE_NOCONTENT);
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
	private BizStep contentReceived(final HttpContent content) {
		updateAndNotifyCurrentProgress(
			TransportUtils.byteBuf2OutputStream(content.content(), this._bytesStream));
		return RECVCONTENT;
	}

	@OnEvent(event = "onLastHttpContentReceived")
	private BizStep lastContentReceived(final LastHttpContent content) throws Exception {
		updateAndNotifyCurrentProgress(
			TransportUtils.byteBuf2OutputStream(content.content(), this._bytesStream));
		
        safeDetachHttpHandle();

        safeRemovePartFromRepo();
        
        final BlobReactor reactor = this._blobReactor;
        this._blobReactor = null;   // clear _blobReactor 字段，这样 onTransactionFailure 不会再被触发
        
        if ( null != reactor) {
            try {
                reactor.onBlobReceived(this._bytesStream.drainToBlob());
                if ( LOG.isTraceEnabled() ) {
                    LOG.trace("blobTransaction invoke onBlobReceived succeed. uri:({})", this._uri);
                }
            }
            catch (Exception e) {
                LOG.warn("exception when BlobReactor.onBlobReceived for uri:{}, detail:{}", 
                        this._uri, ExceptionUtils.exception2detail(e));
            }
        }
        
		return null;
	}

    private void safeRemovePartFromRepo() {
        if ( null != this._partRepo ) {
            try {
                this._partRepo.remove(this._uri);
            }
            catch (Exception e) {
                LOG.warn("exception when _partRepo.remove for uri:{}, detail:{}",
                        this._uri, ExceptionUtils.exception2detail(e));
            }
        }
    }

	@OnEvent(event="detach")
	private BizStep onDetachAndSaveUncompleteContent() throws Exception {
		saveHttpBodyPart();
		if ( LOG.isDebugEnabled() ) {
			LOG.debug("download {} progress canceled", _uri);
		}
        safeDetachHttpHandle();
		return null;
	}

	@OnEvent(event = "onHttpClientLost")
	private BizStep onHttpLostAndSaveUncompleteContent() throws Exception {
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
            final Blob blob = this._bytesStream.drainToBlob();
			try {
			    if ( null != blob ) {
			        final HttpBodyPart bodyPart = new HttpBodyPart(this._response, blob);
			        this._partRepo.put(this._uri, bodyPart);
			        bodyPart.release();
			    }
            } catch (Exception e) {
                LOG.warn("exception when _partRepo.put for uri:{}, detail:{}", 
                        this._uri, ExceptionUtils.exception2detail(e));
            }
			finally {
			    if ( null != blob ) {
			        blob.release();
			    }
			}
		}
	}
	
	private HttpRequest genHttpRequest(final URI uri, final HttpBodyPart part) {
		// Prepare the HTTP request.
		final String host = uri.getHost() == null ? "localhost" : uri.getHost();

		final HttpRequest request = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
		request.headers().set(HttpHeaders.Names.HOST, host);
		
		if ( null == this._policy 
		    || ( null != this._policy && this._policy.gzipEnabled() ) ) {
    		request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING,
    				HttpHeaders.Values.GZIP);
		}
		
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
	
	private void notifyReactorFailureIfNeeded() {
		if ( null != this._blobReactor ) {
			try {
				this._blobReactor.onTransactionFailure(this._failureReason);
			}
			catch (Exception e) {
				LOG.warn("exception when BlobReactor.onTransactionFailure for uri:{}, detail:{}", 
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
        safeReleaseBodyPart();
        
        if ( null != this._partRepo ) {
            try {
                this._bodyPart = this._partRepo.get(this._uri);
            } catch (Exception e) {
                LOG.warn("exception when _partRepo.get for uri:{}, detail:{}", 
                        this._uri, ExceptionUtils.exception2detail(e));
            }
        }
        
        this._handle = this._stack.createHttpClientHandle();
        final HttpClientHandle currentHandle = this._handle;
        
        this._handle.obtainHttpClient( 
                new HttpClientHandle.DefaultContext()
                    .uri(this._uri)
                    .priority( null != this._policy ? this._policy.priority() : 0)
                , 
                new HttpReactor() {
                    @Override
                    public void onHttpClientObtained(HttpClient httpClient)
                            throws Exception {
                        if ( currentHandle == BlobTransactionFlow.this._handle ) {
                            queryInterfaceInstance(HttpReactor.class).onHttpClientObtained(httpClient);
                        }
                        else {
                            LOG.warn("HttpClientHandle mismatch current({})/stored({}), ignore onHttpClientObtained", 
                                    BlobTransactionFlow.this._handle, currentHandle);
                        }
                    }

                    @Override
                    public void onHttpClientLost() throws Exception {
                        if ( currentHandle == BlobTransactionFlow.this._handle ) {
                            queryInterfaceInstance(HttpReactor.class).onHttpClientLost();
                        }
                        else {
                            LOG.warn("HttpClientHandle mismatch current({})/stored({}), ignore onHttpClientLost", 
                                    BlobTransactionFlow.this._handle, currentHandle);
                        }
                    }

                    @Override
                    public void onHttpResponseReceived(HttpResponse response)
                            throws Exception {
                        if ( currentHandle == BlobTransactionFlow.this._handle ) {
                            queryInterfaceInstance(HttpReactor.class).onHttpResponseReceived(response);
                        }
                        else {
                            LOG.warn("HttpClientHandle mismatch current({})/stored({}), ignore onHttpResponseReceived", 
                                    BlobTransactionFlow.this._handle, currentHandle);
                        }
                    }

                    @Override
                    public void onHttpContentReceived(HttpContent content)
                            throws Exception {
                        if ( currentHandle == BlobTransactionFlow.this._handle ) {
                            queryInterfaceInstance(HttpReactor.class).onHttpContentReceived(content);
                        }
                        else {
                            LOG.warn("HttpClientHandle mismatch current({})/stored({}), ignore onHttpContentReceived", 
                                    BlobTransactionFlow.this._handle, currentHandle);
                        }
                    }

                    @Override
                    public void onLastHttpContentReceived(
                            LastHttpContent content) throws Exception {
                        if ( currentHandle == BlobTransactionFlow.this._handle ) {
                            queryInterfaceInstance(HttpReactor.class).onLastHttpContentReceived(content);
                        }
                        else {
                            LOG.warn("HttpClientHandle mismatch current({})/stored({}), ignore onLastHttpContentReceived", 
                                    BlobTransactionFlow.this._handle, currentHandle);
                        }
                    }});
    }

    /**
     * 
     */
    private void safeReleaseBodyPart() {
        if ( null != this._bodyPart ) {
            // release previous
            this._bodyPart.release();
            this._bodyPart = null;
        }
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
    
    private URI _uri;
    private final HttpBodyPartRepo _partRepo;
    private final HttpStack _stack;
	private int    _maxRetryCount = -1;
	private int    _retryCount = 0;
    private long   _timeoutFromActived = -1;
    private long   _timeoutBeforeRetry = 1000L;
    private TransactionPolicy _policy = null;
	private volatile HttpClientHandle _handle;
	private HttpBodyPart _bodyPart = null;
	private HttpResponse _response;
	private long _totalLength = -1;
	private long _currentPos = -1;
    private Detachable _forceFinishedTimer;
    private int _failureReason = TransactionConstants.FAILURE_UNKNOWN;
	
	private final PooledBytesOutputStream _bytesStream;
	private BlobReactor _blobReactor;
}
