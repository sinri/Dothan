package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigParser;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.BindException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DothanLifecycleIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(30)
    void occupiedPortFailsStartWithoutPublishingConfiguration() throws Exception {
        Vertx vertx = Vertx.vertx();
        ServerSocket blocker = occupyPort();
        DothanConfigManager manager = new DothanConfigManager();
        DothanRuntime runtime = new DothanRuntime(vertx, manager);
        try {
            Exception error = assertThrows(Exception.class,
                    () -> runtime.start(snapshot(1, route(blocker.getLocalPort(), 3306)))
                            .await(10, TimeUnit.SECONDS));

            assertInstanceOf(BindException.class, rootCause(error));
            assertNull(manager.current());
        } finally {
            runtime.close().await(10, TimeUnit.SECONDS);
            blocker.close();
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(30)
    void partialStartFailureRollsBackEveryPreparedListener() throws Exception {
        Vertx vertx = Vertx.vertx();
        int freePort = availablePort();
        ServerSocket blocker = occupyPort();
        DothanRuntime runtime = new DothanRuntime(vertx, new DothanConfigManager());
        NetServer probe = null;
        try {
            assertThrows(Exception.class,
                    () -> runtime.start(snapshot(1,
                                    route(freePort, 3306) + route(blocker.getLocalPort(), 3307)))
                            .await(10, TimeUnit.SECONDS));

            probe = bind(vertx, freePort);
        } finally {
            if (probe != null) {
                probe.close().await(10, TimeUnit.SECONDS);
            }
            runtime.close().await(10, TimeUnit.SECONDS);
            blocker.close();
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(30)
    void failedReloadKeepsPreviousConfigurationAndListener() throws Exception {
        Vertx vertx = Vertx.vertx();
        NetServer backend = bind(vertx, 0);
        int proxyPort = availablePort();
        ServerSocket blocker = occupyPort();
        DothanConfigManager manager = new DothanConfigManager();
        DothanRuntime runtime = new DothanRuntime(vertx, manager);
        NetClient client = vertx.createNetClient();
        NetSocket socket = null;
        try {
            DothanConfigSnapshot initial = snapshot(1, route(proxyPort, backend.actualPort()));
            runtime.start(initial).await(10, TimeUnit.SECONDS);

            assertThrows(Exception.class,
                    () -> runtime.reload(snapshot(2,
                                    route(proxyPort, backend.actualPort())
                                            + route(blocker.getLocalPort(), backend.actualPort())))
                            .await(10, TimeUnit.SECONDS));

            assertSame(initial, manager.current());
            socket = client.connect(proxyPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
        } finally {
            if (socket != null) {
                socket.close().await(10, TimeUnit.SECONDS);
            }
            runtime.close().await(10, TimeUnit.SECONDS);
            client.close().await(10, TimeUnit.SECONDS);
            blocker.close();
            backend.close().await(10, TimeUnit.SECONDS);
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(30)
    void closeTerminatesActiveConnectionsAndReleasesPort() throws Exception {
        Vertx vertx = Vertx.vertx();
        NetServer backend = bind(vertx, 0);
        int proxyPort = availablePort();
        DothanRuntime runtime = new DothanRuntime(vertx, new DothanConfigManager());
        NetClient client = vertx.createNetClient();
        NetSocket socket = null;
        NetServer probe = null;
        try {
            runtime.start(snapshot(1, route(proxyPort, backend.actualPort())))
                    .await(10, TimeUnit.SECONDS);
            socket = client.connect(proxyPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
            CountDownLatch socketClosed = new CountDownLatch(1);
            socket.closeHandler(ignored -> socketClosed.countDown());

            runtime.close().await(10, TimeUnit.SECONDS);

            assertTrue(socketClosed.await(10, TimeUnit.SECONDS),
                    "runtime close completed before the active client connection closed");
            probe = bind(vertx, proxyPort);
        } finally {
            if (probe != null) {
                probe.close().await(10, TimeUnit.SECONDS);
            }
            if (socket != null) {
                socket.close().await(10, TimeUnit.SECONDS);
            }
            runtime.close().await(10, TimeUnit.SECONDS);
            client.close().await(10, TimeUnit.SECONDS);
            backend.close().await(10, TimeUnit.SECONDS);
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }

    private NetServer bind(Vertx vertx, int port) throws Exception {
        return vertx.createNetServer()
                .connectHandler(socket -> {
                })
                .listen(port, "127.0.0.1")
                .await(10, TimeUnit.SECONDS);
    }

    private DothanConfigSnapshot snapshot(int version, String routes) throws Exception {
        Path path = temporaryDirectory.resolve("lifecycle-" + version + "-" + System.nanoTime() + ".config");
        Files.writeString(path, "# Dothan Config Version " + version + "\n" + routes);
        return DothanConfigParser.parse(path, false);
    }

    private int availablePort() throws Exception {
        try (ServerSocket reservation = occupyPort()) {
            return reservation.getLocalPort();
        }
    }

    private ServerSocket occupyPort() throws Exception {
        return new ServerSocket(0);
    }

    private String route(int listenPort, int backendPort) {
        return "%d:127.0.0.1:%d%n".formatted(listenPort, backendPort);
    }

    private Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
