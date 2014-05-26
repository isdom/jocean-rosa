/**
 * 
 */
package org.jocean.transportclient;


/**
 * @author isdom
 *
 */
public class TransportEvents {
	
	//	params: ChannelHandlerContext ctx
	public static final String CHANNEL_REGISTERED 		= "_channelRegistered";
	
	//	params: ChannelHandlerContext ctx, event removed from netty-all 5.0.0.Alpha1
//	public static final String EVENT_CHANNELUNREGISTERED 	= "_channelUnregistered";
	
	//	params: ChannelHandlerContext ctx
	public static final String CHANNEL_ACTIVE 			= "_channelActive"; 

	//	params: ChannelHandlerContext ctx
	public static final String CHANNEL_INACTIVE 		= "_channelInactive";

	//	params: ChannelHandlerContext ctx
	public static final String CHANNEL_READCOMPLETE 	= "_channelReadComplete";

	//	params: ChannelHandlerContext ctx, Object event
	public static final String CHANNEL_USEREVENTTRIGGERED 	= "_channeluserEventTriggered";

	//	params: ChannelHandlerContext ctx
	public static final String CHANNEL_WRITABILITYCHANGED = "_channelWritabilityChanged";

	//	params: ChannelHandlerContext ctx, Throwable cause
	public static final String CHANNEL_EXCEPTIONCAUGHT 		= "_channelexceptionCaught";

	//	params: ChannelHandlerContext ctx, Object msg
	public static final String CHANNEL_MESSAGERECEIVED 		= "_channelmessageReceived";
}
