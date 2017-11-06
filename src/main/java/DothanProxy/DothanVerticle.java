package DothanProxy;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;

public class DothanVerticle extends AbstractVerticle {
    
    private int port = 3306;
    private String mysqlHost = "10.10.0.6";
    
    public DothanVerticle(String host,int port){
        super();
        this.mysqlHost=host;
        this.port=port;
    }
    
    @Override
    public void start() throws Exception {
        NetServer netServer = vertx.createNetServer();//创建代理服务器
        NetClient netClient = vertx.createNetClient();//创建连接mysql客户端
        netServer.connectHandler(socket -> netClient.connect(port, mysqlHost, result -> {
            //响应来自客户端的连接请求，成功之后，在建立一个与目标mysql服务器的连接
            if (result.succeeded()) {
                //与目标mysql服务器成功连接连接之后，创造一个MysqlProxyConnection对象,并执行代理方法
                new DothanConnection(socket, result.result()).proxy();
            } else {
                DothanHelper.logger.error(result.cause().getMessage(), result.cause());
                socket.close();
            }
        })).listen(port, listenResult -> {//代理服务器的监听端口
            if (listenResult.succeeded()) {
                //成功启动代理服务器
                DothanHelper.logger.info("Mysql proxy server start up.");
            } else {
                //启动代理服务器失败
                DothanHelper.logger.error("Mysql proxy exit. because: " + listenResult.cause().getMessage(), listenResult.cause());
                System.exit(1);
            }
        });
    }
}