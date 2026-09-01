package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A stable listening port that routes each newly accepted connection using one snapshot.
 */
final class DothanListener implements DothanRuntime.ManagedListener {
    private final Vertx vertx;
    private final DothanConfigManager configManager;
    private final int listenPort;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Set<ConnectionContext> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicBoolean forceClosing = new AtomicBoolean();
    private final AtomicBoolean serverClosing = new AtomicBoolean();
    private final Promise<Void> retirement = Promise.promise();
    private volatile boolean serverClosed;
    private Throwable closeFailure;
    private NetServer server;

    DothanListener(Vertx vertx, DothanConfigManager configManager, int listenPort) {
        this.vertx = vertx;
        this.configManager = configManager;
        this.listenPort = listenPort;
    }

    @Override
    public int port() {
        return listenPort;
    }

    @Override
    public Future<Void> start() {
        server = vertx.createNetServer();
        server.connectHandler(this::accept);
        server.exceptionHandler(error -> logger.error(
                "Listener %d failed: %s".formatted(listenPort, error.getMessage()), error));
        return server.listen(listenPort).mapEmpty();
    }

    private void accept(NetSocket clientSocket) {
        clientSocket.pause();
        if (retired.get()) {
            clientSocket.close();
            return;
        }
        DothanConfigSnapshot snapshot = configManager.current();
        DothanProxyRequirement requirement = snapshot == null
                ? null
                : snapshot.findRequirement(listenPort).orElse(null);
        if (requirement == null) {
            clientSocket.close();
            return;
        }
        String clientAddress = clientSocket.remoteAddress().host();
        ClientAccessPolicy.Decision accessDecision = snapshot.getClientAccessPolicy().evaluate(clientAddress);
        if (!accessDecision.isAllowed()) {
            logger.warn("CLIENT %s rejected on listener %d using config version %d: %s"
                    .formatted(clientAddress, listenPort, snapshot.getVersion(), accessDecision.getReason()));
            clientSocket.close();
            return;
        }

        ConnectionContext connection = null;
        try {
            NetClientOptions clientOptions = new NetClientOptions();
            if (snapshot.getSecureTransportMode() == SecureTransportModeEnum.TLS
                    && snapshot.getTransferMode() == DothanTransferModeEnum.ENCRYPT) {
                clientOptions = TlsTransportOptions.client(snapshot);
            }
            NetClient netClient = vertx.createNetClient(clientOptions);
            connection = new ConnectionContext(clientSocket, netClient);
            connections.add(connection);
            if (retired.get()) {
                connection.closePending();
                return;
            }
            ConnectionContext acceptedConnection = connection;
            Future<Void> incomingReady = snapshot.getSecureTransportMode() == SecureTransportModeEnum.TLS
                    && snapshot.getTransferMode() == DothanTransferModeEnum.DECRYPT
                    ? clientSocket.upgradeToSsl(TlsTransportOptions.server(snapshot))
                    : Future.succeededFuture();
            incomingReady
                    .compose(ignored -> netClient.connect(requirement.serverPort, requirement.serverHost))
                    .onSuccess(serverSocket -> establishConnection(
                            acceptedConnection, serverSocket, snapshot))
                    .onFailure(error -> setupFailed(acceptedConnection, snapshot, error));
        } catch (RuntimeException error) {
            if (connection == null) {
                logger.error("Connection setup failed on listener %d for config version %d: %s"
                        .formatted(listenPort, snapshot.getVersion(), error.getMessage()), error);
                clientSocket.close();
            } else {
                setupFailed(connection, snapshot, error);
            }
        }
    }

    private void establishConnection(ConnectionContext connection, NetSocket serverSocket,
                                     DothanConfigSnapshot snapshot) {
        if (connection.finished.get() || forceClosing.get()) {
            serverSocket.close();
            connection.closePending();
            return;
        }
        try {
            logger.info("PROXY [%s] connected to SERVICE PROVIDER [%s] using config version %d"
                    .formatted(serverSocket.localAddress(), serverSocket.remoteAddress(), snapshot.getVersion()));
            DothanConnection proxy = new DothanConnection(vertx, connection.clientSocket, serverSocket, snapshot,
                    connection::finishedProxy);
            connection.proxy = proxy;
            if (connection.finished.get() || forceClosing.get()) {
                proxy.close();
                return;
            }
            proxy.proxy();
        } catch (RuntimeException error) {
            serverSocket.close();
            setupFailed(connection, snapshot, error);
        }
    }

    private void setupFailed(ConnectionContext connection, DothanConfigSnapshot snapshot, Throwable error) {
        logger.error("Connection setup failed on listener %d for config version %d: %s"
                .formatted(listenPort, snapshot.getVersion(), error.getMessage()), error);
        connection.closePending();
    }

    @Override
    public Future<Void> retire() {
        retired.set(true);
        if (connections.isEmpty()) {
            closeServer();
        }
        return retirement.future();
    }

    @Override
    public Future<Void> closeNow() {
        retired.set(true);
        forceClosing.set(true);
        closeServer();
        connections.forEach(ConnectionContext::closeNow);
        return retirement.future();
    }

    private void closeServer() {
        if (!serverClosing.compareAndSet(false, true)) {
            return;
        }
        if (server == null) {
            serverClosed = true;
            tryCompleteRetirement();
            return;
        }
        server.close().onComplete(result -> {
            if (result.failed()) {
                recordCloseFailure(result.cause());
            }
            serverClosed = true;
            tryCompleteRetirement();
        });
    }

    private synchronized void recordCloseFailure(Throwable error) {
        if (error == null) {
            return;
        }
        if (closeFailure == null) {
            closeFailure = error;
        } else if (closeFailure != error) {
            closeFailure.addSuppressed(error);
        }
    }

    private synchronized void tryCompleteRetirement() {
        if (!serverClosed || !connections.isEmpty()) {
            return;
        }
        if (closeFailure == null) {
            retirement.tryComplete();
        } else {
            retirement.tryFail(closeFailure);
        }
    }

    private final class ConnectionContext {
        private final NetSocket clientSocket;
        private final NetClient netClient;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile DothanConnection proxy;

        private ConnectionContext(NetSocket clientSocket, NetClient netClient) {
            this.clientSocket = clientSocket;
            this.netClient = netClient;
        }

        private void closeNow() {
            DothanConnection activeProxy = proxy;
            if (activeProxy == null) {
                closePending();
            } else {
                activeProxy.close();
            }
        }

        private void closePending() {
            finish(clientSocket.close());
        }

        private void finishedProxy(Throwable socketCloseError) {
            Future<Void> socketClosure = socketCloseError == null
                    ? Future.succeededFuture()
                    : Future.failedFuture(socketCloseError);
            finish(socketClosure);
        }

        private void finish(Future<Void> socketClosure) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            Future.join(socketClosure, netClient.close()).mapEmpty().onComplete(result -> {
                if (result.failed()) {
                    recordCloseFailure(result.cause());
                }
                connections.remove(this);
                if (retired.get() && connections.isEmpty()) {
                    closeServer();
                    tryCompleteRetirement();
                }
            });
        }
    }
}
