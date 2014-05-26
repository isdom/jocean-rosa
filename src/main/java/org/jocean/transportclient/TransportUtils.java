/**
 * 
 */
package org.jocean.transportclient;

import io.netty.channel.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class TransportUtils {

	private static final Logger LOG =
			LoggerFactory.getLogger("transportclient.TransportUtils");
	
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
}
