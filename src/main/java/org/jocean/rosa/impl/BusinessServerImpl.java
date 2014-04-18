/**
 * 
 */
package org.jocean.rosa.impl;

import io.netty.handler.codec.http.HttpRequest;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.QueryParam;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.SimpleCache;
import org.jocean.idiom.Visitor2;
import org.jocean.rosa.api.BusinessServerAgent;
import org.jocean.rosa.api.SignalTransaction;
import org.jocean.rosa.impl.flow.SignalTransactionFlow;
import org.jocean.rosa.impl.flow.SignalTransactionFlow.SignalConverter;
import org.jocean.transportclient.http.HttpStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class BusinessServerImpl implements BusinessServerAgent {

	private static final Logger LOG =
			LoggerFactory.getLogger("rose.impl.BusinessServerImpl");

	//	invoke in main UI thread
    @Override
	public SignalTransaction createSignalTransaction() {
		final SignalTransactionFlow flow = 
				new SignalTransactionFlow(this._stack, this._converter);
		this._source.create(flow, flow.WAIT);
		
		return flow.queryInterfaceInstance(SignalTransaction.class);
	}

	public BusinessServerImpl(
	        final HttpStack httpStack, 
			final EventReceiverSource source) {
		this._stack = httpStack;
		this._source = source;
	}
	
	public BusinessServerImpl registerReuestType(final Class<?> reqCls, final URI uri) {
		this._req2uri.put(reqCls, uri);
		return this;
	}
	
	private final HttpStack _stack;
	private final EventReceiverSource _source;
	private final Map<Class<?>, URI> _req2uri = new HashMap<Class<?>, URI>();
    private final SignalConverter _converter = new SignalConverter() {

        @Override
        public URI req2uri(final Class<?> reqCls) {
            return _req2uri.get(reqCls);
        }

        @Override
        public HttpRequest processHttpRequest(final Object request, final HttpRequest httpRequest) {
            final RequestProcessor processor = _requestProcessorCache.get(
                    request.getClass(), _cacheIfAbsent);

            try {
                processor.visit(request, httpRequest);
            }
            catch (Exception e) {
                LOG.error("exception when process httpRequest ({}) with request bean({})",
                        httpRequest, request);
            }
            
            return httpRequest;
        }};
        
	private final SimpleCache<Class<?>, RequestProcessor> _requestProcessorCache = 
	        new SimpleCache<Class<?>, RequestProcessor>();
	
    private final Function<Class<?>, RequestProcessor> _cacheIfAbsent = 
            new Function<Class<?>, RequestProcessor>() {
                @Override
                public RequestProcessor apply(final Class<?> reqCls) {
                    return new RequestProcessor(reqCls);
                }};
    
	private static final class RequestProcessor implements Visitor2<Object, HttpRequest> {

	    RequestProcessor(final Class<?> reqCls) {
	        this._queryFields = ReflectUtils.getAnnotationFieldsOf(reqCls, QueryParam.class);
	    }
	    
        @Override
        public void visit(final Object request, final HttpRequest httpRequest) 
                throws Exception {
            if ( null != this._queryFields ) {
                final StringBuilder sb = new StringBuilder();
                char link = '?';
                for ( Field field : this._queryFields ) {
                    try {
                        final Object value = field.get(request);
                        if ( null != value ) {
                            final String paramkey = 
                                    field.getAnnotation(QueryParam.class).value();
                            final String paramvalue = 
                                    URLEncoder.encode(String.valueOf(value), "UTF-8");
                            sb.append(link);
                            sb.append(paramkey);
                            sb.append("=");
                            sb.append(paramvalue);
                            link = '&';
                        }
                    }
                    catch (Exception e) {
                        LOG.warn("exception when get field({})'s value, detail:{}", 
                                field, ExceptionUtils.exception2detail(e));
                    }
                }
                
                if ( sb.length() > 0 ) {
                    httpRequest.setUri( httpRequest.getUri() + sb.toString() );
                }
            }
        }
	    
        private final Field[] _queryFields;
	};
}
