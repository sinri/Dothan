package DothanProxy;

import Config.DothanConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;

public class DothanVerticle extends AbstractVerticle {

    private int serverPort;// = 3306;
    private String serverHost;// = "10.10.0.6";

    private int listenPort;// = 3306;

    
    public DothanVerticle(String serverHost,int serverPort,int listenPort){
        super();
        this.serverHost =serverHost;
        this.serverPort = serverPort;
        this.listenPort=listenPort;
    }

    public DothanVerticle(DothanProxyRequirement config) {
        super();
        this.serverHost = config.serverHost;
        this.serverPort = config.serverPort;
        this.listenPort = config.listenPort;
    }

    public void start() {
        NetServer netServer = vertx.createNetServer();//创建代理服务器
        NetClient netClient = vertx.createNetClient();//创建连接mysql客户端
        netServer.connectHandler(socket -> netClient.connect(serverPort, serverHost, result -> {
            //响应来自客户端的连接请求，观测客户端IP，成功之后，建立一个与目标mysql服务器的连接
            if (result.succeeded()) {
                if (!DothanConfig.getInstance().getWhitelist().isEmpty() && !DothanConfig.getInstance().getWhitelist().contains(socket.remoteAddress().host())) {
                    LoggerFactory.getLogger(this.getClass()).error("CLIENT " + socket.remoteAddress().host() + " is not in the whitelist");
                    socket.close();
                }
                if (!DothanConfig.getInstance().getBlacklist().isEmpty() && DothanConfig.getInstance().getBlacklist().contains(socket.remoteAddress().host())) {
                    LoggerFactory.getLogger(this.getClass()).error("CLIENT " + socket.remoteAddress().host() + " is in the blacklist");
                    socket.close();
                }

                //与目标mysql服务器成功连接连接之后，创造一个MysqlProxyConnection对象,并执行代理方法
                new DothanConnection(socket, result.result()).proxy();
                LoggerFactory.getLogger(this.getClass()).info("PROXY [" + result.result().localAddress() + "] successfully connected to SERVICE PROVIDER [" + result.result().remoteAddress() + "]");
            } else {
                if (result.result() == null) {
                    LoggerFactory.getLogger(this.getClass()).error("PROXY failed to connect to SERVICE PROVIDER, NULL RESULT SEEN.");
                } else {
                    LoggerFactory.getLogger(this.getClass()).info("PROXY [" + result.result().localAddress() + "] failed to connect to SERVICE PROVIDER [" + result.result().remoteAddress() + "]");
                }
                LoggerFactory.getLogger(this.getClass()).error(result.cause().getMessage(), result.cause());
                socket.close();
            }
        })).listen(listenPort, listenResult -> {
            // listenPort is 代理服务器的监听端口
            if (listenResult.succeeded()) {
                //成功启动代理服务器
                LoggerFactory.getLogger(this.getClass()).info("PROXY start up " +
                        "for SERVICE PROVIDER " + serverHost + ":" + serverPort + " " +
                        "listening local port " + listenPort + ", actual port " + listenResult.result().actualPort());
            } else {
                //启动代理服务器失败
                LoggerFactory.getLogger(this.getClass()).error("PROXY exit for SERVICE PROVIDER " + serverHost + ":" + serverPort + " " +
                        "and listen port " + listenPort + ", actual port " + listenResult.result().actualPort() +
                        "Reason: " + listenResult.cause().getMessage(), listenResult.cause());
                System.exit(1);
            }
        });
    }
}