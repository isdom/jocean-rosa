/**
 * 
 */
package org.jocean.rosa.api;

/**
 * @author isdom
 *
 */
public class TransactionConstants {
    private static final String[] _REASON_AS_STRING = new String[]{
        "FAILURE_UNKNOWN",
        "FAILURE_RETRY_FAILED",
        "FAILURE_TIMEOUT",
        "FAILURE_NOCONTENT",
        "FAILURE_DOWNLOADABLE_THROW_EXCEPTION",
        "FAILURE_DETACHED"
        };
    
    public static final int FAILURE_UNKNOWN = 0;
    public static final int FAILURE_RETRY_FAILED = 1;
    public static final int FAILURE_TIMEOUT = 2;
    public static final int FAILURE_NOCONTENT = 3;
    public static final int FAILURE_DOWNLOADABLE_THROW_EXCEPTION = 4;
    public static final int FAILURE_DETACHED = 5;
    
    public static String reasonAsString(final int reason) {
        return _REASON_AS_STRING[reason];
    }
}
