/**
 * 
 */
package org.jocean.rosa.api;

/**
 * @author isdom
 *
 */
public class TransactionPolicy {
    
    public TransactionPolicy priority(final int priority) {
        this._priority = priority;
        return this;
    }
    
    public TransactionPolicy interruptLowPriority(final boolean interruptLowPriority) {
        this._interruptLowPriority = interruptLowPriority;
        return this;
    }
    
    public TransactionPolicy maxRetryCount(final int retryCount) {
        this._maxRetryCount = retryCount;
        return this;
    }
    
    public TransactionPolicy timeoutFromActived(final long timeout) {
        this._timeoutFromActived = timeout;
        return this;
    }
    
    public int priority() {
        return this._priority;
    }
    
    public boolean interruptLowPriority() {
        return this._interruptLowPriority;
    }
    
    public int maxRetryCount() {
        return this._maxRetryCount;
    }
    
    public long timeoutFromActived() {
        return this._timeoutFromActived;
    }
    
    private volatile int _maxRetryCount = -1;
    private volatile long _timeoutFromActived = -1;
    private volatile int _priority = 0;
    private volatile boolean _interruptLowPriority = false;
}
