/**
 * 
 */
package org.jocean.rosa.impl;

import io.netty.handler.codec.http.HttpRequest;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.jocean.event.api.EventReceiverSource;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.PropertyPlaceholderHelper;
import org.jocean.idiom.PropertyPlaceholderHelper.PlaceholderResolver;
import org.jocean.idiom.pool.ByteArrayPool;
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
				new SignalTransactionFlow(this._pool, this._stack, this._converter);
		this._source.create(flow, flow.WAIT);
		
		return flow.queryInterfaceInstance(SignalTransaction.class);
	}

	public BusinessServerImpl(
            final ByteArrayPool pool,
	        final HttpStack httpStack, 
			final EventReceiverSource source) {
        this._pool = pool;
		this._stack = httpStack;
		this._source = source;
	}
	
	public BusinessServerImpl registerRequestType(final Class<?> reqCls, final String pathPrefix) {
		this._req2pathPrefix.put(reqCls, pathPrefix);
		return this;
	}
	
    private final ByteArrayPool _pool;
    private final HttpStack _stack;
	private final EventReceiverSource _source;
	
	private final Map<Class<?>, String> _req2pathPrefix = 
	        new ConcurrentHashMap<Class<?>, String>();
	
    private final SignalConverter _converter = new SignalConverter() {

        @Override
        public URI req2uri(final Object request) {
            final String uri = 
                _processorCache.get(request.getClass())
                .apply(request);
            
            try {
                return ( null != uri ? new URI(uri) : null);
            } catch (Exception e) {
                LOG.error("exception when generate URI for request({}), detail:{}",
                        request, ExceptionUtils.exception2detail(e));
                return null;
            }
        }

        @Override
        public HttpRequest processHttpRequest(
                final Object request, 
                final HttpRequest httpRequest) {
            try {
                _processorCache.get(request.getClass())
                    .visit(request, httpRequest);
            }
            catch (Exception e) {
                LOG.error("exception when process httpRequest ({}) with request bean({})",
                        httpRequest, request);
            }
            
            return httpRequest;
        }};
        
	private final SimpleCache<Class<?>, RequestProcessor> _processorCache = 
        new SimpleCache<Class<?>, RequestProcessor>(
            new Function<Class<?>, RequestProcessor>() {
                @Override
                public RequestProcessor apply(final Class<?> reqCls) {
                    return new RequestProcessor(reqCls);
                }});
	
	private final class RequestProcessor 
	    implements Function<Object, String>, Visitor2<Object, HttpRequest> {

	    RequestProcessor(final Class<?> reqCls) {
            this._queryFields = ReflectUtils.getAnnotationFieldsOf(reqCls, QueryParam.class);
            
            this._pathSuffix = getPathValueOf(reqCls);
	        
	        this._pathparamResolver = genPlaceholderResolverOf(reqCls, PathParam.class);

	        this._pathparamReplacer = 
	                ( null != this._pathparamResolver ? new PropertyPlaceholderHelper("{", "}") : null);
	    }

        @Override
        public String apply(final Object request) {
            final String pathPrefix = _req2pathPrefix.get(request.getClass());
            if ( null == pathPrefix && null == this._pathSuffix ) {
                // class not registered, return null
                return null;
            }
            
            final String fullPath = safeConcatPath(pathPrefix, this._pathSuffix );
            if ( null != this._pathparamReplacer ) {
                return this._pathparamReplacer.replacePlaceholders(
                        request,
                        fullPath, 
                        this._pathparamResolver, 
                        null);
            }
            else {
                return fullPath;
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
        
        private final String _pathSuffix;
        
        private final PropertyPlaceholderHelper _pathparamReplacer;
        
        private final PlaceholderResolver _pathparamResolver;
	};
	
    /**
     * @param cls
     */
    private static String getPathValueOf(final Class<?> cls) {
        final Path path = cls.getAnnotation(Path.class);
        return (null != path ? path.value() : null);
    }

    private static String safeConcatPath(final String pathPrefix, final String pathSuffix) {
        if ( null == pathSuffix ) {
            return pathPrefix;
        }
        if ( null == pathPrefix ) {
            return pathSuffix;
        }
        return pathPrefix + pathSuffix;
    }

    /**
     * @return
     */
    private static PlaceholderResolver genPlaceholderResolverOf(
            Class<?> cls, Class<? extends Annotation> annotationCls) {
        final Map<String, Field> pathparam2fields = 
            genPath2FieldMapping(
                ReflectUtils.getAnnotationFieldsOf(cls, annotationCls));
        
        final Map<String, Method> pathparam2methods = 
            genPath2MethodMapping(cls, 
                ReflectUtils.getAnnotationMethodsOf(cls, annotationCls));
        
        if ( null == pathparam2fields 
           && null ==  pathparam2methods) {
            return null;
        }
        
        return new PropertyPlaceholderHelper.PlaceholderResolver() {
            @Override
            public String resolvePlaceholder(final Object request,
                    final String placeholderName) {
                if (null != pathparam2fields) {
                    final Field field = pathparam2fields
                            .get(placeholderName);
                    if (null != field) {
                        try {
                            return String.valueOf(field.get(request));
                        } catch (Exception e) {
                            LOG.error("exception when get value for ({}).{}, detail: {}",
                                    request, field.getName(),
                                    ExceptionUtils.exception2detail(e));
                        }
                    }
                }

                if ( null != pathparam2methods ) {
                    final Method method = pathparam2methods
                            .get(placeholderName);
                    if (null != method) {
                        try {
                            return String.valueOf(method.invoke(request));
                        } catch (Exception e) {
                            LOG.error("exception when invoke ({}).{}, detail: {}",
                                    request, method.getName(),
                                    ExceptionUtils.exception2detail(e));
                        }
                    }
                }

                // default by empty string, so placeholder will be erased
                // from uri
                return "";
            }
        };
    }
    
    private static Map<String, Field> genPath2FieldMapping(
            final Field[] pathparamFields) {
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
    
    private static Map<String, Method> genPath2MethodMapping(
            final Class<?> cls, 
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
                            cls, method.getName());
                }
            }
            return ( !ret.isEmpty() ?  ret : null);
        }
        else {
            return null;
        }
    }
    
}
