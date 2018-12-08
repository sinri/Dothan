package DothanProxy;

import Config.DothanConfig;
import Security.CryptAgentOfAES;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferFactoryImpl;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;

import java.util.Objects;

class DothanConnection {
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
            LoggerFactory.getLogger(this.getClass()).error("SERVICE PROVIDER Socket Exception Occurred. " + e.getMessage(), e);
            close();
        });
        clientSocket.exceptionHandler(e -> {
            LoggerFactory.getLogger(this.getClass()).error("CLIENT Socket Exception Occurred. " + e.getMessage(), e);
            close();
        });

        clientSocket.handler(buffer -> {
            switch (DothanConfig.getInstance().getTransferMode()) {
                case DECRYPT:
                    Buffer decryptedBuffer = new BufferFactoryImpl().buffer(Objects.requireNonNull(CryptAgentOfAES.decryptBytes(buffer.getBytes(), DothanConfig.getInstance().getTransferKey())));
                    if (DothanConfig.getInstance().isVerbose()) {
                        LoggerFactory.getLogger(DothanConnection.this.getClass()).info("From CLIENT [" + clientSocket.remoteAddress() + "], request length: " + buffer.length() + " DECRYPT result length: " + decryptedBuffer.length());
                    }
                    serverSocket.write(decryptedBuffer);
                    break;
                case ENCRYPT:
                    Buffer encryptedBuffer = new BufferFactoryImpl().buffer(Objects.requireNonNull(CryptAgentOfAES.encryptBytes(buffer.getBytes(), DothanConfig.getInstance().getTransferKey())));
                    if (DothanConfig.getInstance().isVerbose()) {
                        LoggerFactory.getLogger(DothanConnection.this.getClass()).info("From CLIENT [" + clientSocket.remoteAddress() + "], request length: " + buffer.length() + " ENCRYPT result length: " + encryptedBuffer.length());
                    }
                    serverSocket.write(encryptedBuffer);
                    break;
                case PLAIN:
                default:
                    if (DothanConfig.getInstance().isVerbose()) {
                        LoggerFactory.getLogger(DothanConnection.this.getClass()).info("From CLIENT [" + clientSocket.remoteAddress() + "], request length: " + buffer.length());
                    }
                    serverSocket.write(buffer);
                    break;
            }

        });
        serverSocket.handler(buffer -> {
            if (DothanConfig.getInstance().isVerbose()) {
                LoggerFactory.getLogger(this.getClass()).info("From SERVICE PROVIDER [" + serverSocket.remoteAddress() + "], response length: " + buffer.length());
//                DothanHelper.logger.info(buffer);
//                DothanHelper.logger.info(buffer.getString(0, buffer.length()));
            }
            clientSocket.write(buffer);
        });

        // pure original
        //当收到来自客户端的数据包时，转发给mysql目标服务器
        //clientSocket.handler(serverSocket::write);
        //当收到来自mysql目标服务器的数据包时，转发给客户端
        //serverSocket.handler(clientSocket::write);
    }

    public void close() {
        clientSocket.close();
        serverSocket.close();
    }

}
