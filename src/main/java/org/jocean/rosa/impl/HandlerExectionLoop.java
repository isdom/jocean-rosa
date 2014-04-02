/**
 * 
 */
package org.jocean.rosa.impl;

import org.jocean.idiom.Detachable;
import org.jocean.idiom.ExectionLoop;

import android.os.Handler;

/**
 * @author isdom
 *
 */
public class HandlerExectionLoop implements ExectionLoop {

    @Override
    public boolean inExectionLoop() {
        return (Thread.currentThread().getId() == this._handler.getLooper().getThread().getId());
    }

    @Override
    public Detachable submit(final Runnable runnable) {
        final Runnable wrap = new Runnable() {
            @Override
            public void run() {
                runnable.run();
            }};
        this._handler.post(wrap);
        return new Detachable() {
            @Override
            public void detach() {
                _handler.removeCallbacks(wrap);
            }};
    }

    @Override
    public Detachable schedule(final Runnable runnable, final long delayMillis) {
        final Runnable wrap = new Runnable() {
            @Override
            public void run() {
                runnable.run();
            }};
        this._handler.postDelayed(wrap, delayMillis);
        return new Detachable() {
            @Override
            public void detach() {
                _handler.removeCallbacks(wrap);
            }};
    }

    public HandlerExectionLoop(final Handler handler) {
        this._handler = handler;
    }
    
    private final Handler _handler;
}
