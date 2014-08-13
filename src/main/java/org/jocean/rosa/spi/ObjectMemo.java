/**
 * 
 */
package org.jocean.rosa.spi;


/**
 * @author isdom
 *
 */
public interface ObjectMemo {
    
    public void updateToMemo(final String key, final Object obj);
    
    public void removeFromMemo(final String key);
}
