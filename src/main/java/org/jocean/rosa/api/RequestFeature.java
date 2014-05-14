/**
 * 
 */
package org.jocean.rosa.api;


/**
 * @author isdom
 *
 */
public enum RequestFeature {
    EnableJsonCompress;
    
    private RequestFeature(){
        mask = (1 << ordinal());
    }

    private final int mask;

    public final int getMask() {
        return mask;
    }

    public static boolean isEnabled(final int features, final RequestFeature feature) {
        return (features & feature.getMask()) != 0;
    }

    public static int config(int features, final RequestFeature feature, final boolean state) {
        if (state) {
            features |= feature.getMask();
        } else {
            features &= ~feature.getMask();
        }

        return features;
    }
    
    public static int features2int(final RequestFeature[] features) {
        int featuresAsInt = 0;
        for ( RequestFeature feature : features) {
            featuresAsInt = config(featuresAsInt, feature, true);
        }
        
        return featuresAsInt;
    }
}
