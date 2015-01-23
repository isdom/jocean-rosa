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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jocean.event.api.AbstractFlow;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.annotation.OnDelayed;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.httpclient.api.Guide;
import org.jocean.httpclient.api.Guide.GuideReactor;
import org.jocean.httpclient.api.HttpClient;
import org.jocean.httpclient.api.HttpClient.HttpReactor;
import org.jocean.httpclient.api.GuideBuilder;
import org.jocean.httpclient.impl.HttpUtils;
import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ValidationId;
import org.jocean.idiom.block.Blob;
import org.jocean.idiom.pool.BytesPool;
import org.jocean.rosa.api.DownloadAgent.DownloadReactor;
import org.jocean.rosa.api.TransactionConstants;
import org.jocean.rosa.api.TransactionPolicy;
import org.jocean.rosa.spi.Downloadable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class DownloadFlow extends AbstractFlow<DownloadFlow> {

	private static final Logger LOG = LoggerFactory
			.getLogger(DownloadFlow.class);

    public DownloadFlow(
            final BytesPool pool,
            final GuideBuilder guideBuilder) {
        this._guideBuilder = guideBuilder;
        this._bytesPool = pool;
        
        addFlowLifecycleListener(new FlowLifecycleListener<DownloadFlow>() {

            @Override
            public void afterEventReceiverCreated(
                    final DownloadFlow flow, final EventReceiver receiver)
                    throws Exception {
            }

            @Override
            public void afterFlowDestroy(final DownloadFlow flow)
                    throws Exception {
                try {
                    safeDetachForceFinishedTimer();
                }
                finally {
                    notifyReactorFailureIfNeeded();
                }
            }} );
    }

    private final Object ON_HTTPLOST = new Object() {
        @OnEvent(event = "onHttpClientLost")
        private BizStep onHttpLost(final int guideId)
                throws Exception {
            if ( !isValidGuideId(guideId) ) {
                return currentEventHandler();
            }
            
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("http for download {} lost.", _downloadable);
            }
            notifyReactorTransportInactived();
            return incRetryAndSelectStateByRetry();
        }
        
        @OnEvent(event = "detachHttpClient")
        private BizStep onDetachHttpClient()
                throws Exception {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("download {} on reserved event: detachHttpClient", _downloadable);
            }
            safeDetachHttpGuide();
            return currentEventHandler();
        }
    };
    
    private final Object ON_DETACH = new Object() {
        @OnEvent(event="detach")
        private BizStep onDetach() throws Exception {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("download {} canceled", _downloadable);
            }
            safeDetachHttpGuide();
            setFailureDetachedIfNotSetted();
            return null;
        }
    };
    
	public final BizStep WAIT = new BizStep("download.WAIT") {
        @OnEvent(event = "start")
        private BizStep onTaskStart(
                final Object ctx,
                final Downloadable downloadable,
                final DownloadReactor<Object, Downloadable> reactor, 
                final TransactionPolicy policy) {
            _context = ctx;
            _downloadable = downloadable;
            _downloadReactor = reactor;
            
            if ( null != policy ) {
                _maxRetryCount = policy.maxRetryCount();
                _timeoutFromActived = policy.timeoutFromActived();
                _timeoutBeforeRetry = Math.max( policy.timeoutBeforeRetry(), _timeoutBeforeRetry);
                _policy = policy;
            }
            
            doObtainHttpClient();
            return OBTAINING;
        }
	}
	.handler(handlersOf(ON_DETACH) )
	.freeze();
	
	private final BizStep OBTAINING = new BizStep("download.OBTAINING") {
	    @OnEvent(event = "onHttpClientObtained")
	    private BizStep onHttpObtained(final int guideId, final HttpClient httpclient) {
	        if ( !isValidGuideId(guideId) ) {
	            return currentEventHandler();
	        }
	        
	        if ( null != _downloadReactor ) {
	            try {
	                _downloadReactor.onTransportActived(_context, _downloadable);
	            }
	            catch (Throwable e) {
	                LOG.warn("exception when DownloadReactor.onTransportActived for {}, detail:{}", 
	                        _downloadable, ExceptionUtils.exception2detail(e));
	            }
	        }
	        final HttpRequest request = genHttpRequest(_downloadable);
	        if ( LOG.isDebugEnabled() ) {
	            LOG.debug("send http request {}", request);
	        }
	        try {
	            httpclient.sendHttpRequest(_httpClientId.updateIdAndGet(), request, genHttpReactor() );
	        }
	        catch (Throwable e) {
	            LOG.error("state({})/{}: exception when sendHttpRequest, detail:{}", 
	                    currentEventHandler().getName(), currentEvent(), ExceptionUtils.exception2detail(e));
	        }
	        tryStartForceFinishedTimer();
	        return RECVRESP;
	    }
	}
	.handler(handlersOf(ON_HTTPLOST))
    .handler(handlersOf(ON_DETACH) )
	.freeze();

	private final BizStep RECVRESP = new BizStep("download.RECVRESP") {
        @OnEvent(event = "onHttpResponseReceived")
        private BizStep responseReceived(final int httpClientId, final HttpResponse response) {
            if ( !isValidHttpClientId(httpClientId)) {
                return currentEventHandler();
            }
            
            _downloadable.updateResponse(response);
            
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("download for {} recv response {}", _downloadable, response);
            }
            
            if ( !response.getStatus().equals(HttpResponseStatus.OK)
                && !response.getStatus().equals(HttpResponseStatus.PARTIAL_CONTENT)) {
                
                if ( response.getStatus().equals(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE)) {
//                    HTTP/1.1 416 Requested Range Not Satisfiable
//                    Server: marco/0.1
//                    Date: Tue, 19 Aug 2014 09:42:32 GMT
//                    Content-Type: application/vnd.android.package-archive
//                    Content-Length: 7037018
//                    Connection: keep-alive
//                    X-Source: U/200
//                    Last-Modified: Fri, 01 Aug 2014 09:54:58 GMT
//                    Expires: Wed, 20 Aug 2014 05:32:21 GMT
//                    Cache-Control: max-age=677841
//                    Accept-Ranges: bytes
//                    Age: 196850
//                    X-Cache: HIT from cun-sd-wef-102, MISS(S)|HIT from cun-zj-huz-004
//                    Content-Range: bytes 1766185-8803202/8803203 
                    //  需要处理上述 特例，返回值 为 416，但仍然返回了 Content-Range: bytes 1766185-8803202/8803203，
                    //  并且起始字节数和 http request 中的要求字节数是一致的
                    final Long partialBegin = checkAndGetPartialBeginFromContentRange(response);
                    if ( null != partialBegin 
                        && _downloadable.getDownloadedSize() == partialBegin) {
                        return resumeDownload(response);
                    }
                    else {
                        //  Content-Range 不存在 或 Content-Range 中的 partialBegin 与 downloadedSize 不一致
                        // 416 Requested Range Not Satisfiable
                        // 清除 PARTIAL 后，再次尝试完整获取 url
                        // 此前的 httpClientHandle 已经 detach
                        // so 如下直接开始重新获取
                        return resetAndStartNewDownload();
                    }
                }
                else {
                    safeDetachHttpGuide();
                    setFailureReason(TransactionConstants.FAILURE_NOCONTENT);
                    return null;
                }
            }
            
            //  http response status is HttpResponseStatus.OK || HttpResponseStatus.PARTIAL_CONTENT
            final Long partialBegin = checkAndGetPartialBeginFromContentRange(response);
            if ( null != partialBegin 
                 && _downloadable.getDownloadedSize() != partialBegin ) {
                LOG.warn("download for {}, partial begin position({}) !NOT! equals local position({}), restart full download.", 
                        _downloadable, partialBegin, _downloadable.getDownloadedSize());
                return resetAndStartNewDownload();
            }
            
            return resumeDownload(response);
        }

        /**
         * @return
         */
        private BizStep resetAndStartNewDownload() {
            safeDetachHttpGuide();
            resetDownloadedContent();
            doObtainHttpClient();
            return OBTAINING;
        }

        /**
         * @param response
         * @return
         */
        private BizStep resumeDownload(final HttpResponse response) {
            _totalLength = HttpUtils.getContentTotalLengthFromResponseAsLong(response, -1);
            
            if ( LOG.isInfoEnabled() ) {
                LOG.info("download for {}, begin download from {} and total size {}", _downloadable, _downloadable.getDownloadedSize(), _totalLength);
            }
            
            notifyContentType(response.headers().get(HttpHeaders.Names.CONTENT_TYPE));
            
            notifyCurrentProgress();
            
            if ( HttpUtils.isHttpResponseHasMoreContent(response) ) {
                return RECVCONTENT;
            }
            else {
                LOG.warn("download for {} has no content, so end download", _downloadable);
                setFailureReason(TransactionConstants.FAILURE_NOCONTENT);
                safeDetachHttpGuide();
                return null;
            }
        }
    }
    .handler(handlersOf(ON_HTTPLOST))
    .handler(handlersOf(ON_DETACH) )
	.freeze();
	
	private final BizStep RECVCONTENT = new BizStep("download.RECVCONTENT") {
	    @OnEvent(event = "onHttpContentReceived")
	    private BizStep contentReceived(final int httpClientId, final HttpContent content) 
	            throws Exception {
	        if ( !isValidHttpClientId(httpClientId)) {
	            return currentEventHandler();
	        }
	        
	        final Blob blob = HttpUtils.httpContent2Blob(_bytesPool, content);
	        try {
    	        if ( !updateDownloadableContent(blob) ) {
    	            return null;
    	        }
    	        else {
    	            return RECVCONTENT;
    	        }
	        } finally {
	            if (null != blob ) {
	                blob.release();
	            }
	        }
	    }
	    
	    @OnEvent(event = "onLastHttpContentReceived")
	    private BizStep lastContentReceived(final int httpClientId, final LastHttpContent content) 
	            throws Exception {
	        if ( !isValidHttpClientId(httpClientId)) {
	            return currentEventHandler();
	        }
	        
            final Blob blob = HttpUtils.httpContent2Blob(_bytesPool, content);
            try {
    	        if ( !updateDownloadableContent(blob) ) {
    	            return null;
    	        }
            } finally {
                if (null != blob ) {
                    blob.release();
                }
            }
	        
	        safeDetachHttpGuide();

	        final DownloadReactor<Object, Downloadable> reactor = _downloadReactor;
	        _downloadReactor = null;   // clear _downloadReactor 字段，这样 onDownloadFailure 不会再被触发
	        
	        if ( null != reactor) {
	            try {
	                reactor.onDownloadSucceed(_context, _downloadable);
	                if ( LOG.isTraceEnabled() ) {
	                    LOG.trace("download for {} onDownloadSucceed succeed.", _downloadable);
	                }
	            }
	            catch (Throwable e) {
	                LOG.warn("exception when DownloadReactor.onDownloadSucceed for {}, detail:{}", 
	                        _downloadable, ExceptionUtils.exception2detail(e));
	            }
	        }
	        
	        return null;
	    }

	}
    .handler(handlersOf(ON_HTTPLOST))
    .handler(handlersOf(ON_DETACH) )
	.freeze();

    private final BizStep SCHEDULE = new BizStep("download.SCHEDULE") {
        @OnEvent(event="detach")
        private BizStep schedulingOnDetach() throws Exception {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("download blob {} when scheduling and canceled", _downloadable);
            }
            removeAndCancelAllDealyEvents(_timers);
            safeDetachHttpGuide();
            setFailureDetachedIfNotSetted();
            return null;
        }
        
        @OnDelayed
        private BizStep onScheduled() {
            doObtainHttpClient();
            return OBTAINING;
        }
    }
    .freeze();
    
    private Long checkAndGetPartialBeginFromContentRange(final HttpResponse response) {
        try {
            final String contentRange = response.headers().get(HttpHeaders.Names.CONTENT_RANGE);
            if ( null != contentRange ) {
                if ( LOG.isInfoEnabled() ) {
                    LOG.info("download for {}, recv partial response, detail: {}", _downloadable, contentRange);
                }
                
                // 考虑 Content-Range 的情况
                final String partialBegin = HttpUtils.getPartialBeginFromContentRange(contentRange);
                if ( null != partialBegin) {
                    return Long.parseLong(partialBegin);
                }
            }
        }
        catch (Throwable e) {
            LOG.warn("exception when checkAndGetPartialBeginFromContentRange for {}, detail: {}",
                    _downloadable, ExceptionUtils.exception2detail(e));
        }
        
        return null;
    }

    private void safeDetachForceFinishedTimer() {
        if ( null != this._forceFinishedTimer) {
            try {
                this._forceFinishedTimer.detach();
            } catch (Throwable e) {
                LOG.warn("exception when _forceFinishedTimer.detach for {}, detail:{}", 
                        this._downloadable, ExceptionUtils.exception2detail(e));
            }
            this._forceFinishedTimer = null;
        }
    }

	private BizStep incRetryAndSelectStateByRetry() {
        this._retryCount++;
        if ( this._maxRetryCount < 0 ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("uri:{} 's max retry count < 0, so retry forever, now retry count is {}.", 
                   this._downloadable, this._retryCount);
            }
            return  delayRetry();
        }
        else {
            if ( this._retryCount <= this._maxRetryCount ) {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("uri:{} 's retry count is {}, when max retry {}, so retry.", 
                       this._downloadable, this._retryCount, this._maxRetryCount);
                }
                return delayRetry();
            }
            else {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("uri:{} 's retry count is {} reached max retry {}, so blob download canceled.",
                       this._downloadable, this._retryCount, this._maxRetryCount);
                }
                this.setFailureReason(TransactionConstants.FAILURE_RETRY_FAILED);
                return null;
            }
        }
    }

    private BizStep delayRetry() {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("delay {}s and retry fetch blob uri:{}", this._timeoutBeforeRetry / 1000, this._downloadable);
        }
        
        tryStartForceFinishedTimer();
        return ((BizStep)this.fireDelayEventAndAddTo(
                this.SCHEDULE.makePredefineDelayEvent(this._timeoutBeforeRetry), 
                this._timers))
                .freeze();
    }
    
    private void resetDownloadedContent() {
        this._downloadable.resetDownloadedContent();
    }

    @SuppressWarnings("unchecked")
    private HttpReactor<Integer> genHttpReactor() {
        return (HttpReactor<Integer>)this.queryInterfaceInstance(HttpReactor.class);
    }

    private void tryStartForceFinishedTimer() {
        if ( null == this._forceFinishedTimer && this._timeoutFromActived > 0) {
		    this._forceFinishedTimer = this.selfExectionLoop().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        if ( LOG.isDebugEnabled() ) {
                            LOG.debug("uri:{} force finished timeout, so force detach.", _downloadable);
                        }
                        _forceFinishedTimer = null;
                        setFailureReason(TransactionConstants.FAILURE_TIMEOUT);
                        selfEventReceiver().acceptEvent("detach");
                    } catch (Throwable e) {
                        LOG.warn("exception when acceptEvent detach by force finished for uri:{}, detail:{}", 
                                _downloadable, ExceptionUtils.exception2detail(e));
                    }
                }}, this._timeoutFromActived);
		}
    }


	private void notifyContentType(final String contentType) {
        if ( null != this._downloadReactor ) {
            try {
                this._downloadReactor.onContentTypeReceived(this._context, this._downloadable, contentType);
            }
            catch (Throwable e) {
                LOG.warn("exception when DownloadReactor.onContentTypeReceived for {} contentType:{}, detail:{}", 
                        this._downloadable, contentType, ExceptionUtils.exception2detail(e));
            }
        }
    }

    /**
     * @param contentBlob
     */
    private boolean updateDownloadableContent(final Blob contentBlob) {
        try {
            final int bytesAdded = this._downloadable.appendDownloadedContent(contentBlob);
            updateAndNotifyCurrentProgress( bytesAdded );
            return true;
        }
        catch (Throwable e) {
            LOG.warn("exception when Dowmloadable.appendDownloadedContent for {}, detail: {}", 
                    this._downloadable, ExceptionUtils.exception2detail(e));
            safeDetachHttpGuide();
            this.setFailureReason(TransactionConstants.FAILURE_DOWNLOADABLE_THROW_EXCEPTION);
            return false;
        }
    }


    private void updateAndNotifyCurrentProgress(final long bytesAdded) {
		if ( bytesAdded > 0 ) {
			notifyCurrentProgress();
		}
	}
	
	private void notifyCurrentProgress() {
		if ( null != this._downloadReactor ) {
			try {
				this._downloadReactor.onProgress(this._context, this._downloadable, 
				        this._downloadable.getDownloadedSize(), this._totalLength);
			}
			catch (Throwable e) {
				LOG.warn("exception when DownloadReactor.onProgress for {} progress{}/{}, detail:{}", 
						this._downloadable, this._downloadable.getDownloadedSize(), 
						this._totalLength, ExceptionUtils.exception2detail(e));
			}
		}
	}

	private HttpRequest genHttpRequest(final Downloadable downloadable) {
		// Prepare the HTTP request.
	    final URI uri = downloadable.getUri();
		final String host = uri.getHost() == null ? "localhost" : uri.getHost();

		final HttpRequest request = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
		request.headers().set(HttpHeaders.Names.HOST, host);
		
		if ( null == this._policy 
		    || ( null != this._policy && this._policy.gzipEnabled() ) ) {
    		request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING,
    				HttpHeaders.Values.GZIP);
		}
		
		if ( downloadable.isPartialDownload() ) {
			//	add Range info
			request.headers().set(HttpHeaders.Names.RANGE, "bytes=" + downloadable.getDownloadedSize() + "-");
			final String etag = downloadable.getEtag();
			if ( null != etag ) {
				request.headers().set(HttpHeaders.Names.IF_RANGE, etag);
			}
			if ( LOG.isInfoEnabled() ) {
    			LOG.info("download for {}, send partial request, detail: Range:{}/If-Range:{}", downloadable, 
    					request.headers().get(HttpHeaders.Names.RANGE), 
    					request.headers().get(HttpHeaders.Names.IF_RANGE));
			}
		}
		
		return request;
	}
	
	private void notifyReactorFailureIfNeeded() {
		if ( null != this._downloadReactor ) {
			try {
				this._downloadReactor.onDownloadFailure(this._context, this._downloadable, this._failureReason);
			}
			catch (Throwable e) {
				LOG.warn("exception when DownloadReactor.onDownloadFailure for uri:{}, detail:{}", 
						this._downloadable, ExceptionUtils.exception2detail(e));
			}
		}
	}
	
	private void notifyReactorTransportInactived() {
		if ( null != this._downloadReactor ) {
			try {
				this._downloadReactor.onTransportInactived(this._context, this._downloadable);
			}
			catch (Throwable e) {
				LOG.warn("exception when imageReactor.onTransportInactived for uri:{}, detail:{}", 
						this._downloadable, ExceptionUtils.exception2detail(e));
			}
		}
	}

    private void doObtainHttpClient() {
        this._guide = this._guideBuilder.createHttpClientGuide();
        
        this._guide.obtainHttpClient(
                this._guideId.updateIdAndGet(), 
                genGuideReactor(),
                new Guide.DefaultRequirement()
                    .uri(this._downloadable.getUri())
                    .priority( null != this._policy ? this._policy.priority() : 0)
            );
    }

    @SuppressWarnings("unchecked")
    private GuideReactor<Integer> genGuideReactor() {
        return (GuideReactor<Integer>)queryInterfaceInstance(GuideReactor.class);
    }

    private void safeDetachHttpGuide() {
        if ( null != this._guide ) {
            try {
                this._guide.detach();
            }
            catch (Throwable e) {
                LOG.warn("exception when detach http guide for download {}, detail:{}",
                        this._downloadable, ExceptionUtils.exception2detail(e));
            }
            this._guide = null;
        }
    }
    
    private void setFailureReason(final int failureReason) {
        this._failureReason = failureReason;
    }
    
    private boolean isValidGuideId(final int guideId) {
        final boolean ret = this._guideId.isValidId(guideId);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "DownloadFlow({})/{}/{}: special guide id({}) is !NOT! current guide id ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        guideId, this._guideId);
            }
        }
        return ret;
    }
    
    private boolean isValidHttpClientId(final int httpClientId) {
        final boolean ret = this._httpClientId.isValidId(httpClientId);
        if (!ret) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "DownloadFlow({})/{}/{}: special httpclient id({}) is !NOT! current httpclient id ({}), just ignore.",
                        this, currentEventHandler().getName(), currentEvent(),
                        httpClientId, this._httpClientId);
            }
        }
        return ret;
    }
    
    private void setFailureDetachedIfNotSetted() {
        if ( TransactionConstants.FAILURE_UNKNOWN == this._failureReason ) {
            this.setFailureReason(TransactionConstants.FAILURE_DETACHED);
        }
    }

    private Downloadable _downloadable;
    private final GuideBuilder _guideBuilder;
    private final BytesPool _bytesPool;
	private int    _maxRetryCount = -1;
	private int    _retryCount = 0;
    private long   _timeoutFromActived = -1;
    private long   _timeoutBeforeRetry = 1000L;
    private TransactionPolicy _policy = null;
	private Guide _guide;
    private final ValidationId _guideId = new ValidationId();
    private final ValidationId _httpClientId = new ValidationId();
	private long _totalLength = -1;
    private Detachable _forceFinishedTimer;
    private int _failureReason = TransactionConstants.FAILURE_UNKNOWN;
	
	private DownloadReactor<Object, Downloadable> _downloadReactor;
	private Object _context;
    private final List<Detachable> _timers = new ArrayList<Detachable>();
}
