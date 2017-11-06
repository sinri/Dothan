package DothanProxy;

import io.vertx.core.net.NetSocket;

public class DothanConnection {
    private final NetSocket clientSocket;
    private final NetSocket serverSocket;
    
    public DothanConnection(NetSocket clientSocket, NetSocket serverSocket) {
        this.clientSocket = clientSocket;
        this.serverSocket = serverSocket;
    }
    
    public void proxy() {
        //当代理与mysql服务器连接关闭时，关闭client与代理的连接
        serverSocket.closeHandler(v -> clientSocket.close());
        //反之亦然
        clientSocket.closeHandler(v -> serverSocket.close());
        //不管那端的连接出现异常时，关闭两端的连接
        serverSocket.exceptionHandler(e -> {
            DothanHelper.logger.error(e.getMessage(), e);
            close();
        });
        clientSocket.exceptionHandler(e -> {
            DothanHelper.logger.error(e.getMessage(), e);
            close();
        });
        //当收到来自客户端的数据包时，转发给mysql目标服务器
        clientSocket.handler(serverSocket::write);
        //当收到来自mysql目标服务器的数据包时，转发给客户端
        serverSocket.handler(clientSocket::write);
    }
    
    public void close() {
        clientSocket.close();
        serverSocket.close();
    }

}
