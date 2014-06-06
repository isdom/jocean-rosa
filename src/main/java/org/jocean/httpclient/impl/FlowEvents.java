/**
 * 
 */
package org.jocean.httpclient.impl;

/**
 * @author isdom
 *
 */
final class FlowEvents {
    //  internal events define
    
    //  from mediator -> guide
    static final String NOTIFY_GUIDE_FOR_CHANNEL_RESERVED = "_notify_guide_for_channel_reserved";
    
    //  from mediator -> guide
    static final String REQUEST_CHANNEL_PUBLISH_STATE = "_request_channel_publish_state";
    
    //  from guide -> channel
    static final String REQUEST_CHANNEL_BIND_WITH_GUIDE = "_request_channel_bind_with_guide";
    
    //  from channel -> guide
    static final String NOTIFY_GUIDE_FOR_CHANNEL_BINDED = "_notify_guide_for_channel_binded";
    
    //  from channel -> guide
    static final String NOTIFY_GUIDE_FOR_HTTPCLIENT_OBTAINED = "_notify_guide_for_httpclient_obtained";
    
    //  from channel -> guide
    static final String NOTIFY_GUIDE_FOR_CHANNEL_LOST = "_notify_guide_for_channel_lost";
}
