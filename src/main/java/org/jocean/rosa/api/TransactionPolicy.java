/**
 * 
 */
package org.jocean.rosa.api;

/**
 * @author isdom
 *
 */
public class TransactionPolicy {
    
    public TransactionPolicy maxRetryCount(final int retryCount) {
        this._maxRetryCount = retryCount;
        return this;
    }
    
    public TransactionPolicy timeoutFromActived(final long timeout) {
        this._timeoutFromActived = timeout;
        return this;
    }
    
    public int maxRetryCount() {
        return this._maxRetryCount;
    }
    
    public long timeoutFromActived() {
        return this._timeoutFromActived;
    }
    
    private volatile int _maxRetryCount = -1;
    private volatile long _timeoutFromActived = -1;
}
