/**
 * 
 */
package org.jocean.rosa.spi;


/**
 * @author isdom
 *
 */
public interface DownloadMemo<DOWNLOAD extends Downloadable> {
    
    public void updateToMemo(final DOWNLOAD downloadable);
    
    public void removeFromMemo(final DOWNLOAD downloadable);
}
