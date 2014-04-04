package org.jocean.rosa.api;

import java.io.InputStream;
import java.util.Collection;

/**
 * @author isdom
 *
 */
public interface Blob {
    
    public int length();
    
    public InputStream genInputStream();
    
    public Collection<byte[]> bytesCollection();
}
