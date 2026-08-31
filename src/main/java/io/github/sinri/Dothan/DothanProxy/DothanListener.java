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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stable listening port that routes each newly accepted connection using one snapshot.
 */
final class DothanListener implements DothanRuntime.ManagedListener {
    private final Vertx vertx;
    private final DothanConfigManager configManager;
    private final int listenPort;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Promise<Void> retirement = Promise.promise();
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
        if (requirement == null || !allowed(snapshot, clientSocket)) {
            clientSocket.close();
            return;
        }

        activeConnections.incrementAndGet();
        if (retired.get()) {
            clientSocket.close();
            connectionFinished(null);
            return;
        }
        NetClient netClient = null;
        try {
            NetClientOptions clientOptions = new NetClientOptions();
            if (snapshot.getSecureTransportMode() == SecureTransportModeEnum.TLS
                    && snapshot.getTransferMode() == DothanTransferModeEnum.ENCRYPT) {
                clientOptions = TlsTransportOptions.client(snapshot);
            }
            netClient = vertx.createNetClient(clientOptions);
            NetClient connectionClient = netClient;
            Future<Void> incomingReady = snapshot.getSecureTransportMode() == SecureTransportModeEnum.TLS
                    && snapshot.getTransferMode() == DothanTransferModeEnum.DECRYPT
                    ? clientSocket.upgradeToSsl(TlsTransportOptions.server(snapshot))
                    : Future.succeededFuture();
            incomingReady
                    .compose(ignored -> connectionClient.connect(requirement.serverPort, requirement.serverHost))
                    .onSuccess(serverSocket -> establishConnection(
                            clientSocket, serverSocket, connectionClient, snapshot))
                    .onFailure(error -> setupFailed(clientSocket, connectionClient, snapshot, error));
        } catch (RuntimeException error) {
            setupFailed(clientSocket, netClient, snapshot, error);
        }
    }

    private void establishConnection(NetSocket clientSocket, NetSocket serverSocket, NetClient netClient,
                                     DothanConfigSnapshot snapshot) {
        try {
            logger.info("PROXY [%s] connected to SERVICE PROVIDER [%s] using config version %d"
                    .formatted(serverSocket.localAddress(), serverSocket.remoteAddress(), snapshot.getVersion()));
            new DothanConnection(vertx, clientSocket, serverSocket, snapshot,
                    () -> connectionFinished(netClient)).proxy();
        } catch (RuntimeException error) {
            serverSocket.close();
            setupFailed(clientSocket, netClient, snapshot, error);
        }
    }

    private void setupFailed(NetSocket clientSocket, NetClient netClient,
                             DothanConfigSnapshot snapshot, Throwable error) {
        logger.error("Connection setup failed on listener %d for config version %d: %s"
                .formatted(listenPort, snapshot.getVersion(), error.getMessage()), error);
        clientSocket.close();
        connectionFinished(netClient);
    }

    private boolean allowed(DothanConfigSnapshot snapshot, NetSocket socket) {
        String host = socket.remoteAddress().host();
        if (!snapshot.getWhitelist().isEmpty() && !snapshot.getWhitelist().contains(host)) {
            logger.warn("CLIENT %s is not in the whitelist".formatted(host));
            return false;
        }
        if (snapshot.getBlacklist().contains(host)) {
            logger.warn("CLIENT %s is in the blacklist".formatted(host));
            return false;
        }
        return true;
    }

    private void connectionFinished(NetClient netClient) {
        if (netClient != null) {
            netClient.close();
        }
        int remaining = activeConnections.decrementAndGet();
        if (retired.get() && remaining == 0) {
            closeServer();
        }
    }

    @Override
    public Future<Void> retire() {
        retired.set(true);
        if (activeConnections.get() == 0) {
            closeServer();
        }
        return retirement.future();
    }

    @Override
    public Future<Void> closeNow() {
        retired.set(true);
        closeServer();
        return retirement.future();
    }

    private void closeServer() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (server == null) {
            retirement.tryComplete();
            return;
        }
        server.close().onComplete(retirement);
    }
}
