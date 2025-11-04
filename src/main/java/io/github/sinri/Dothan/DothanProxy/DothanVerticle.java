package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;

public class DothanVerticle extends AbstractVerticle {

    private final int serverPort;// = 3306;
    private final String serverHost;// = "10.10.0.6";

    private final int listenPort;// = 3306;


    public DothanVerticle(String serverHost, int serverPort, int listenPort) {
        super();
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.listenPort = listenPort;
    }

    public DothanVerticle(DothanProxyRequirement config) {
        super();
        this.serverHost = config.serverHost;
        this.serverPort = config.serverPort;
        this.listenPort = config.listenPort;
    }

    public void start() {
        Logger logger = LoggerFactory.getLogger(this.getClass());
        NetServer netServer = vertx.createNetServer();//创建代理服务器
        NetClient netClient = vertx.createNetClient();//创建连接mysql客户端
        netServer.connectHandler(socket -> {
                     logger.info("Dothan Verticle Connect Handler set up for %s which come to %s".formatted(socket.remoteAddress(), socket.localAddress()));
                     netClient.connect(serverPort, serverHost)
                              .onComplete(result -> {
                                  //响应来自客户端的连接请求，观测客户端IP，成功之后，建立一个与目标mysql服务器的连接
                                  if (result.succeeded()) {
                                      if (!DothanConfig.getInstance().getWhitelist().isEmpty()
                                              && !DothanConfig.getInstance().getWhitelist()
                                                              .contains(socket.remoteAddress().host())
                                      ) {
                                          logger.error("CLIENT %s is not in the whitelist".formatted(socket.remoteAddress()
                                                                                                           .host()));
                                          socket.close();
                                      }
                                      if (!DothanConfig.getInstance().getBlacklist().isEmpty()
                                              && DothanConfig.getInstance()
                                                             .getBlacklist()
                                                             .contains(socket.remoteAddress()
                                                                             .host())) {
                                          logger.error("CLIENT %s is in the blacklist".formatted(socket.remoteAddress()
                                                                                                       .host()));
                                          socket.close();
                                      }

                                      //与目标mysql服务器成功连接连接之后，创造一个MysqlProxyConnection对象,并执行代理方法
                                      new DothanConnection(socket, result.result()).proxy();
                                      logger.info("PROXY [%s] successfully connected to SERVICE PROVIDER [%s]".formatted(result.result()
                                                                                                                               .localAddress(), result.result()
                                                                                                                                                      .remoteAddress()));
                                  } else {
                                      if (result.result() == null) {
                                          logger.error("PROXY failed to connect to SERVICE PROVIDER, NULL RESULT SEEN.");
                                      } else {
                                          logger.info("PROXY [%s] failed to connect to SERVICE PROVIDER [%s]".formatted(result.result()
                                                                                                                              .localAddress(), result.result()
                                                                                                                                                     .remoteAddress()));
                                      }
                                      logger.error(result.cause().getMessage(), result.cause());
                                      socket.close();
                                  }
                              });
                 })
                 .listen(listenPort)
                 .onComplete(listenResult -> {
                     // listenPort is 代理服务器的监听端口
                     if (listenResult.succeeded()) {
                         //成功启动代理服务器
                         logger.info("PROXY start up for SERVICE PROVIDER %s:%d listening local port %d, actual port %d".formatted(serverHost, serverPort, listenPort, listenResult.result()
                                                                                                                                                                                   .actualPort()));
                     } else {
                         //启动代理服务器失败
                         int actualPort = -1;
                         if (listenResult.result() != null) actualPort = listenResult.result().actualPort();
                         logger.error("listenResult.result is %s".formatted(listenResult.result()));
                         logger.error("PROXY exit for SERVICE PROVIDER %s:%d and listen port %d, actual port %d Reason: %s".formatted(serverHost, serverPort, listenPort, actualPort, listenResult.cause()
                                                                                                                                                                                                  .getMessage()), listenResult.cause());
                         //System.exit(1);
                         try {
                             logger.warn("Let this DothanVerticle stop. This would never be started again until next restart manually or called by new config version.");
                             this.stop();
                         } catch (Exception e) {
                             logger.warn("Failed to make this DothanVerticle stop: %s".formatted(e.getMessage()));
                             e.printStackTrace();
                         }
                     }
                 });
    }
}