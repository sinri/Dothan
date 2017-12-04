package DothanProxy;

import io.vertx.core.AbstractVerticle;
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

    public DothanVerticle(DothanConfigItem config) {
        super();
        this.serverHost = config.serverHost;
        this.serverPort = config.serverPort;
        this.listenPort = config.listenPort;
    }

    public void start() {
        NetServer netServer = vertx.createNetServer();//创建代理服务器
        NetClient netClient = vertx.createNetClient();//创建连接mysql客户端
        netServer.connectHandler(socket -> netClient.connect(serverPort, serverHost, result -> {
            //响应来自客户端的连接请求，成功之后，在建立一个与目标mysql服务器的连接
            if (result.succeeded()) {
                //与目标mysql服务器成功连接连接之后，创造一个MysqlProxyConnection对象,并执行代理方法
                new DothanConnection(socket, result.result()).proxy();
                DothanHelper.logger.info("Successfully connected to database " + serverHost + ":" + serverPort + "; " +
                        "Remote: " + result.result().remoteAddress() + " Local: " + result.result().localAddress());
            } else {
                DothanHelper.logger.error("Failed to connect to database " + serverHost + ":" + serverPort + "; " +
                        "Remote: " + result.result().remoteAddress() + " Local: " + result.result().localAddress());
                DothanHelper.logger.error(result.cause().getMessage(), result.cause());
                socket.close();
            }
        })).listen(listenPort, listenResult -> {//代理服务器的监听端口
            if (listenResult.succeeded()) {
                //成功启动代理服务器
                DothanHelper.logger.info("MySQL proxy server start up " +
                        "for database " + serverHost + ":" + serverPort + " " +
                        "listening local port " + listenPort + ", actual port " + listenResult.result().actualPort());
            } else {
                //启动代理服务器失败
                DothanHelper.logger.error("Mysql proxy exit for database " + serverHost + ":" + serverPort + " " +
                        "and listen port " + listenPort + ", actual port " + listenResult.result().actualPort() +
                        "Reason: " + listenResult.cause().getMessage(), listenResult.cause());
                System.exit(1);
            }
        });
    }
}