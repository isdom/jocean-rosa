/**
 * 
 */
package org.jocean.rosa.spi;

import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;

import org.jocean.idiom.block.Blob;

/**
 * @author isdom
 *
 */
public interface Downloadable {
    
    public URI getUri();
    
    public int getDownloadedSize();

    public int appendDownloadedContent(final Blob contentBlob) throws Exception;
    
    public void resetDownloadedContent();

    public void updateResponse(final HttpResponse response);

    public String getEtag();

    public boolean isPartialDownload();
}
