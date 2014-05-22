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
  
