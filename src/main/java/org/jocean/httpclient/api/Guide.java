/**
 * 
 */
package org.jocean.httpclient.api;

import java.net.URI;
import java.util.Comparator;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface Guide extends Detachable {
    
    public static final Comparator<Requirement> ASC_COMPARATOR = new Comparator<Requirement> () {
        @Override
        public int compare(final Requirement o1, final Requirement o2) {
            return o1.priority() - o2.priority();
        }
    };
    
    public static final Comparator<Requirement> DESC_COMPARATOR = new Comparator<Requirement> () {
        @Override
        public int compare(final Requirement o1, final Requirement o2) {
            return o2.priority() - o1.priority();
        }
    };
    
    public interface Requirement {
        public int priority();
        public URI uri();
    }

    public class DefaultRequirement implements Requirement {

        public DefaultRequirement priority(final int priority) {
            this._priority = priority;
            return this;
        }
        
        public DefaultRequirement uri(final URI uri) {
            this._uri = uri;
            return this;
        }
        
        @Override
        public int priority() {
            return this._priority;
        }

        @Override
        public URI uri() {
            return this._uri;
        }
        
        @Override
        public String toString() {
            return "DefaultRequirement [uri=" + _uri  + ", priority=" + _priority + "]";
        }

        private volatile int _priority = 0;
        private volatile URI _uri;
    }
    
    public interface GuideReactor<CTX> {
        
        public void onHttpClientObtained(final CTX ctx, final HttpClient httpClient) throws Exception;
        
        public void onHttpClientLost(final CTX ctx) throws Exception;
    }
    
    public <CTX> void obtainHttpClient(final CTX ctx, final GuideReactor<CTX> reactor, final Requirement requirement);
}
