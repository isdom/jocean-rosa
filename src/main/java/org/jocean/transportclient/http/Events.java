/**
 * 
 */
package org.jocean.transportclient.http;

/**
 * @author isdom
 *
 */
final class Events {
    final static class FlowEvents {
        //  internal events define
        static final String START_ATTACH = "_start_attach";
        static final String START_ATTACH_FAILED = "_start_attach_failed";
        static final String ATTACHING = "_attaching";
        static final String ATTACHING_FAILED = "_attaching_failed";
        static final String ATTACHED = "_attached";
        static final String ATTACHED_FAILED = "_attached_failed";
        static final String CHANNELLOST = "_channelLost";

    }

    final static class HttpEvents {
        //  HTTP events
        //  params: ChannelHandlerContext ctx, HttpResponse response
        static final String HTTPRESPONSERECEIVED    = "_httpResponseReceived";

        //  params: ChannelHandlerContext ctx, HttpContent content
        static final String HTTPCONTENTRECEIVED     = "_httpContentReceived";

        //  params: ChannelHandlerContext ctx, LastHttpContent lastContent
        static final String LASTHTTPCONTENTRECEIVED= "_lastHttpContentReceived";
    }
}
