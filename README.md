jocean-rosa
===========

jocean's rosa: Remote Operations Service Agent

实现基于HTTP交互的 信令 和 文件 上下行 封装。
主要基于 jocean-idiom、jocean-event-api、jocean-transportclient 实现，其 异步nio使用 netty 4.x (目前使用 4.0.23.Final)

TODO:
  1、在 DownloadFlow 中根据 HttpResponse 返回的
    Last-Modified: Fri, 01 Aug 2014 09:54:58 GMT
    Expires: Sun, 17 Aug 2014 23:05:12 GMT
    Cache-Control: max-age=679065
  等字段信息进行细致的缓存的超时控制。

2014-09-30:  release 0.0.9 版本：
  1、重构httpclient部分的实现代码，将原有通过MediatorFlow 实现的 协调 Guide & Channel 之间的业务逻辑，调整为主要由 Guide & Channel之间直接通讯完成。
  2、用 Mediator 代替 MediatorFlow 实现 协调guide & channel 的业务逻辑，从原先的事件处理方式 更改为多线程下可重入的方法调用
  3、fix bug: 对于实际上匹配的既有domain 的 uri，因为缺省端口号没有明确写出(http-80,https-443)导致判断为不同的domain，而对于redommend消息，误返回recommend level 为IDLE_BUT_NOT_MATCH的情况。
  4、更新 netty 依赖到 4.0.23.Final 版本(解决问题的版本为 4.0.20.Final)，解决 https 在特殊情况下，握手完成后，解析服务端发过来的数据出现无限循环，并导致频繁GC的问题。导致https信令交互无限等待的BUG。
  
2014-08-19:  release 0.0.8 版本：
  1、处理HTTP续传情况下，Http Response 状态码为 416，但仍然返回了 Content-Range头域，并且续传起始字节数和HttpRequest 中的要求字节数是一致的情况，此时，继续正常的续传流程
  2、添加并实现 ContentTask.detachHttpClient 接口方法，具体实现方法是在 DownloadAgent中增加detachHttpClientOf方法，而该方法的实现是向DownloadTask发送 "detachHttpClient"事件
  3、基于 jocean-event-api-0.0.5-SNAPSHOT 中提供的 内嵌BizStep派生类/实例，自动注册OnEvent/OnDelayed 注解的方法作为事件/延迟事件 作为事件处理方法 的特性，改造 BusinessServerAgent模块的流程可读性
  4、使用 GuardReferenceCounted 注解 和 RefcountedGuardEventable 简化事件参数中有ReferenceCounted 类型时的 参数保护，代替原有在 Flow 实现时，实现 ArgsHandler 的方式，事件参数的保护针对特定事件
  5、实现 RemoteContentAgent 接口，用于下载及管理远程内容， 具体实现是用 DiskLruCache 实现 文件下载记录的保存
  6、增加 外部调用 detach 时，在 onDownloadFailure 时，返回 FAILURE_DETACHED 作为 reason值。
  7、基于 jocean-event-api-0.0.5-SNAPSHOT 中提供的  内嵌BizStep派生类/实例，自动注册OnEvent/OnDelayed 注解的方法作为事件/延迟事件 作为事件处理方法 的特性，改造 httpclient 模块的流程可读性
  8、实现通用下载agent：DownloadAgent：基于BlobAgent 修改，将固定的内存保存下载内容通过Downloadable扩展为 可基于文件保存 下载片段和最终的下载文件。
  
2014-06-11:  release 0.0.7 版本：
  1、将 jocean-transportclient-0.0.8-release 合并到 jocean-rosa模块中。
  2、http://rdassist.widget-inc.com:65480/browse/CHANNEL-138：
     2.1、包修改: org.jocean.transportclient => 改进为 org.jocean.httpclient.* & org.jocean.netty.*
     2.2、HttpHandle => Guide，HandleFlow => GuideFlow
     2.3、将HttpStack 中的调度代码分离为 独立的长事务 MediatorFlow，基于event机制，支持多线程方式下的调度代码串行支持；
     2.4、将原有的 ChannelFlow & HandleFlow(GuideFlow) 中的 Holder接口 更新为Publisher接口，并由MediatorFlow提供对应的接口转事件处理，因此可异步处理Guide & Channel状态变化，响应异步更新MediatorFlow中记录对应状态；特别的，对于预留的Channel 因为源Guide失效，而需要被再次纳入到对应的资源池的处理，通过由ChannelFlow在所有状态均响应事件:REQUEST_CHANNEL_PUBLISH_STATE来实现，而在MediatorFlow中，只需要在正确的时机，发送REQUEST_CHANNEL_PUBLISH_STATE事件即可

2014-05-22： release 0.0.6 版本：
  1、支持JSR339的POST注解，发送POST请求，现在用于上行JSON数据
  2、对 上行 signal 采用 JZlib 进行 最佳压缩，使用 mimetype 为 application/cjson
  3、BusinessServerImpl.registerRequestType支持注册时，指定Request产生HTTP请求时的各类特性，目前定义了EnableJsonCompress特定，启用POST
  4、修改 jocean-rose 中得 BloblAgent & BusinessServerAgent接口方法，将BlobTransaction/BlobReactor & SignalTransaction/SignalReactor迁移到对应的 XXXAgent 接口定义内；并在所有的Reactor 中添加 CTX ctx 回传上下文实例
  5、fix bug: 当 Request 没有被显式用 registerRequestType 注册，而仅通过 @Path标注目标uri时，会出现NPE
  6、根据 HttpReactor的接口修改调整代码：onHttpContentReceived & onLastHttpContentReceived回调方法的参数：HttpContent 更改为 Blob 类型
