/**
 * 
 */
package org.jocean.transportclient.api;

import java.net.URI;
import java.util.Comparator;

import org.jocean.idiom.Detachable;

/**
 * @author isdom
 *
 */
public interface HttpClientHandle extends Detachable {
    
    public static final Comparator<Context> ASC_COMPARATOR = new Comparator<Context> () {
        @Override
        public int compare(final Context o1, final Context o2) {
            return o1.priority() - o2.priority();
        }
    };
    
    public static final Comparator<Context> DESC_COMPARATOR = new Comparator<Context> () {
        @Override
        public int compare(final Context o1, final Context o2) {
            return o2.priority() - o1.priority();
        }
    };
    
    public interface Context {
        public int priority();
        public URI uri();
    }

    public class DefaultContext implements Context {

        public DefaultContext priority(final int priority) {
            this._priority = priority;
            return this;
        }
        
        public DefaultContext uri(final URI uri) {
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
            return "context [priority=" + _priority + ", uri=" + _uri
                    + "]";
        }

        private volatile int _priority = 0;
        private volatile URI _uri;
    }
    
	public void obtainHttpClient(final Context ctx, final HttpReactor reactor);
}
