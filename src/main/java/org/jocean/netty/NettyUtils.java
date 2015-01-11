/**
 * 
 */
package org.jocean.netty;

import io.netty.channel.Channel;
import io.netty.util.ReferenceCounted;

import org.jocean.idiom.PairedVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class NettyUtils {

	private static final Logger LOG =
			LoggerFactory.getLogger(NettyUtils.class);
	
	public static void awaitForAllEnded(final Channel[] channels) throws Exception {
    	try {
            // Wait for the server to close the connection.
    		while (true) {
	            boolean isAllClosed = true;
	            for ( Channel ch : channels) {
	            	if ( !ch.closeFuture().isDone() ) {
	            		isAllClosed = false;
	            	}
	            }
	            if ( isAllClosed ) {
	            	break;
	            }
	            LOG.info("some channels still actived, wait for 1000ms and re-check");
	            Thread.sleep(1000);
    		}
        }
    	finally {
            // Shut down executor threads to exit.
            //_bootstrap.group().shutdownGracefully();
		}
    }

    public static PairedVisitor<Object> _NETTY_REFCOUNTED_GUARD = new PairedVisitor<Object>() {

        @Override
        public void visitBegin(final Object obj) {
            if ( obj instanceof ReferenceCounted ) {
                ((ReferenceCounted)obj).retain();
            }
        }

        @Override
        public void visitEnd(final Object obj) {
            if ( obj instanceof ReferenceCounted ) {
                ((ReferenceCounted)obj).release();
            }
        }
        
        @Override
        public String toString() {
            return "NettyUtils._NETTY_REFCOUNTED_GUARD";
        }};
}
