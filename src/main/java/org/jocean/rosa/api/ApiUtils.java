/**
 * 
 */
package org.jocean.rosa.api;

import org.jocean.idiom.ExectionLoop;
import org.jocean.rosa.impl.HandlerExectionLoop;


import android.os.Handler;

/**
 * @author isdom
 *
 */
public class ApiUtils {
    
    public static ExectionLoop genExectionLoopOf(final Handler handler) {
        return new HandlerExectionLoop(handler);
    }
}
