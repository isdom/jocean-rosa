jocean-rosa
===========

jocean's rosa: Remote Operations Service Agent

实现基于HTTP交互的 信令 和 文件 上下行 封装。
主要基于 jocean-idiom、jocean-event-api、jocean-transportclient 实现，其 异步nio使用 netty 4.x (目前使用 4.0.18.Final)

2014-05-22： release 0.0.6 版本：
  1、支持JSR339的POST注解，发送POST请求，现在用于上行JSON数据
  2、对 上行 signal 采用 JZlib 进行 最佳压缩，使用 mimetype 为 application/cjson
  3、BusinessServerImpl.registerRequestType支持注册时，指定Request产生HTTP请求时的各类特性，目前定义了EnableJsonCompress特定，启用POST
  4、修改 jocean-rose 中得 BloblAgent & BusinessServerAgent接口方法，将BlobTransaction/BlobReactor & SignalTransaction/SignalReactor迁移到对应的 XXXAgent 接口定义内；并在所有的Reactor 中添加 CTX ctx 回传上下文实例
  5、fix bug: 当 Request 没有被显式用 registerRequestType 注册，而仅通过 @Path标注目标uri时，会出现NPE
  6、根据 HttpReactor的接口修改调整代码：onHttpContentReceived & onLastHttpContentReceived回调方法的参数：HttpContent 更改为 Blob 类型

2014-06-11:  release 0.0.7 版本：
  1、将 jocean-transportclient-0.0.8-release 合并到 jocean-rosa模块中。
  2、http://rdassist.widget-inc.com:65480/browse/CHANNEL-138：
     2.1、包修改: org.jocean.transportclient => 改进为 org.jocean.httpclient.* & org.jocean.netty.*
     2.2、HttpHandle => Guide，HandleFlow => GuideFlow
     2.3、将HttpStack 中的调度代码分离为 独立的长事务 MediatorFlow，基于event机制，支持多线程方式下的调度代码串行支持；
     2.4、将原有的 ChannelFlow & HandleFlow(GuideFlow) 中的 Holder接口 更新为Publisher接口，并由MediatorFlow提供对应的接口转事件处理，因此可异步处理Guide & Channel状态变化，响应异步更新MediatorFlow中记录对应状态；特别的，对于预留的Channel 因为源Guide失效，而需要被再次纳入到对应的资源池的处理，通过由ChannelFlow在所有状态均响应事件:REQUEST_CHANNEL_PUBLISH_STATE来实现，而在MediatorFlow中，只需要在正确的时机，发送REQUEST_CHANNEL_PUBLISH_STATE事件即可
     
