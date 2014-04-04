/**
 * 
 */
package org.jocean.rosa;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.jocean.idiom.ByteArrayListInputStream;
import org.jocean.rosa.api.Blob;

/**
 * @author isdom
 *
 */
public class DefaultBlob implements Blob {

    public DefaultBlob(final Collection<byte[]> bytesCollecion) {
        this._bytesList = new ArrayList<byte[]>(bytesCollecion.size());
        this._bytesList.addAll(bytesCollecion);
        
        this._length = sizeOf(this._bytesList);
    }
    
    @Override
    public int length() {
        return this._length;
    }

    @Override
    public InputStream genInputStream() {
        return new ByteArrayListInputStream(this._bytesList);
    }

    public Collection<byte[]> bytesCollection() {
        return  Collections.unmodifiableCollection(this._bytesList);
    }
    
    private static int sizeOf(final Collection<byte[]> bytesList) {
        int totalSize = 0;
        for ( byte[] bytes : bytesList) {
            totalSize += bytes.length;
        }
        
        return totalSize;
    }
    
    private final List<byte[]> _bytesList;
    private final int _length;
}
