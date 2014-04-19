/**
 * 
 */
package org.jocean.rosa.impl;

import io.netty.handler.codec.http.HttpRequest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.PropertyPlaceholderHelper;
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
	
	public BusinessServerImpl registerReuestType(final Class<?> reqCls, final String uri) {
		this._req2uri.put(reqCls, uri);
		return this;
	}
	
	private final HttpStack _stack;
	private final EventReceiverSource _source;
	private final Map<Class<?>, String> _req2uri = new HashMap<Class<?>, String>();
    private final SignalConverter _converter = new SignalConverter() {

        @Override
        public URI req2uri(final Object request) {
            final RequestProcessor processor = _requestProcessorCache.get(
                    request.getClass(), _cacheIfAbsent);
            
            final String suffix = processor.apply(request);
            
            try {
                if ( null == suffix ) {
                    return new URI(_req2uri.get(request.getClass()));
                }
                else {
                    return new URI(_req2uri.get(request.getClass()) + suffix);
                }
            } catch (Exception e) {
                LOG.error("exception when generate URI for request({}), detail:{}",
                        request, ExceptionUtils.exception2detail(e));
                return null;
            }
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
    
	private static final class RequestProcessor 
	    implements Function<Object, String>, Visitor2<Object, HttpRequest> {

	    RequestProcessor(final Class<?> reqCls) {
	        final Path path = reqCls.getAnnotation(Path.class);
	        this._requestPath = null != path ? path.value() : null;
	        this._queryFields = ReflectUtils.getAnnotationFieldsOf(reqCls, QueryParam.class);
	        
	        this._pathparam2fields = genPath2FieldMapping(
	                ReflectUtils.getAnnotationFieldsOf(reqCls, PathParam.class));
	        
            this._pathparam2methods = genPath2MethodMapping(reqCls, 
                    ReflectUtils.getAnnotationMethodsOf(reqCls, PathParam.class));
	        
	    }

        private Map<String, Field> genPath2FieldMapping(final Field[] pathparamFields) {
	        if ( null != pathparamFields ) {
	            final Map<String, Field> ret = new HashMap<String, Field>();
	            for ( Field field : pathparamFields ) {
	                ret.put(field.getAnnotation(PathParam.class).value(), field);
	            }
	            return ret;
	        }
	        else {
	            return null;
	        }
        }
	    
        private Map<String, Method> genPath2MethodMapping(final Class<?> reqCls, 
                final Method[] pathparamMethods) {
            if ( null != pathparamMethods ) {
                final Map<String, Method> ret = new HashMap<String, Method>();
                for ( Method method : pathparamMethods ) {
                    if ( method.getParameterTypes().length == 0 
                        && !method.getReturnType().equals(void.class)) {
                        ret.put(method.getAnnotation(PathParam.class).value(), method);
                    }
                    else {
                        LOG.warn("class({}).{} can't be invoke as PathParam, just ignore",
                                reqCls, method.getName());
                    }
                }
                return ( !ret.isEmpty() ?  ret : null);
            }
            else {
                return null;
            }
        }
        
        @Override
        public String apply(final Object request) {
            if ( null != this._pathparam2fields && null != this._requestPath ) {
                return this._placeholderHelper.replacePlaceholders(
                        request,
                        this._requestPath, 
                        this._placeholderResolver, 
                        null);
            }
            else {
                return null;
            }
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
        private final Map<String, Field> _pathparam2fields;
        private final Map<String, Method> _pathparam2methods;
        private final String _requestPath;
        
        private final PropertyPlaceholderHelper _placeholderHelper = 
                new PropertyPlaceholderHelper("{", "}");
        
        private final PropertyPlaceholderHelper.PlaceholderResolver _placeholderResolver = 
                new PropertyPlaceholderHelper.PlaceholderResolver() {
            @Override
            public String resolvePlaceholder(final Object request, final String placeholderName) {
                final Field field = _pathparam2fields.get(placeholderName);
                if ( null != field ) {
                    try {
                        return String.valueOf( field.get(request) );
                    }
                    catch (Exception e) {
                        LOG.error("exception when get value for ({}).{}, detail: {}", 
                                request, field.getName(), ExceptionUtils.exception2detail(e));
                    }
                }
                
                final Method method = _pathparam2methods.get(placeholderName);
                if ( null != method ) {
                    try {
                        return String.valueOf( method.invoke(request) );
                    }
                    catch (Exception e) {
                        LOG.error("exception when invoke ({}).{}, detail: {}", 
                                request, method.getName(), ExceptionUtils.exception2detail(e));
                    }
                }
                
                // default by empty string, so placeholder will be erased from uri
                return "";
            }};
	};
}
