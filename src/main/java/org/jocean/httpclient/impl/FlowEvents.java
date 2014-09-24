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
    static final String NOTIFY_GUIDE_START_SELECTING = "_notify_guide_start_selecting";
    
    //  from guide -> channel
    static final String RECOMMEND_CHANNEL_FOR_BINDING = "_recommend_channel_for_binding";
    
    //  from guide -> channel
    static final String REQUEST_CHANNEL_BIND_WITH_GUIDE = "_request_channel_bind_with_guide";
    
    //  from channel -> guide
    static final String NOTIFY_GUIDE_FOR_CHANNEL_BINDED = "_notify_guide_for_channel_binded";
    
    //  from channel -> guide
    static final String NOTIFY_GUIDE_FOR_HTTPCLIENT_OBTAINED = "_notify_guide_for_httpclient_obtained";
    
    //  from channel -> guide
    static final String NOTIFY_GUIDE_FOR_CHANNEL_LOST = "_notify_guide_for_channel_lost";
}
