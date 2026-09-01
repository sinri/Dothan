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

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAccessIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(60)
    void whitelistAndBlacklistRejectionsNeverReachBackend() throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicInteger backendConnections = new AtomicInteger();
        CountDownLatch backendAccepted = new CountDownLatch(1);
        NetServer backend = null;
        NetClient client = null;
        DothanRuntime runtime = null;
        try {
            backend = backend(vertx, backendConnections, backendAccepted);
            int proxyPort = availablePort();
            DothanConfigManager manager = new DothanConfigManager();
            runtime = new DothanRuntime(vertx, manager);
            runtime.start(snapshot(1, proxyPort, backend.actualPort(), "+ 192.0.2.1"))
                    .await(10, TimeUnit.SECONDS);

            client = vertx.createNetClient();
            connectAndClose(client, proxyPort);

            runtime.reload(snapshot(2, proxyPort, backend.actualPort(), "- 127.0.0.1"))
                    .await(10, TimeUnit.SECONDS);
            connectAndClose(client, proxyPort);

            assertFalse(backendAccepted.await(500, TimeUnit.MILLISECONDS),
                    "a rejected client opened a backend connection");
            assertEquals(0, backendConnections.get());
        } finally {
            close(runtime, client, backend, vertx);
        }
    }

    @Test
    @Timeout(60)
    void hotReloadUsesTheNewAccessPolicyForNewConnections() throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicInteger backendConnections = new AtomicInteger();
        CountDownLatch firstBackendAccept = new CountDownLatch(1);
        NetServer backend = null;
        NetClient client = null;
        DothanRuntime runtime = null;
        try {
            backend = backend(vertx, backendConnections, firstBackendAccept);
            int proxyPort = availablePort();
            DothanConfigManager manager = new DothanConfigManager();
            runtime = new DothanRuntime(vertx, manager);
            runtime.start(snapshot(1, proxyPort, backend.actualPort(), ""))
                    .await(10, TimeUnit.SECONDS);

            client = vertx.createNetClient();
            connectAndClose(client, proxyPort);
            assertTrue(firstBackendAccept.await(10, TimeUnit.SECONDS));
            assertEquals(1, backendConnections.get());

            runtime.reload(snapshot(2, proxyPort, backend.actualPort(), "- 127.0.0.1"))
                    .await(10, TimeUnit.SECONDS);
            connectAndClose(client, proxyPort);

            assertEquals(1, backendConnections.get());
            assertFalse(waitForConnections(backendConnections, 2, 500),
                    "the post-reload rejected client opened a backend connection");
        } finally {
            close(runtime, client, backend, vertx);
        }
    }

    private NetServer backend(Vertx vertx, AtomicInteger connections, CountDownLatch accepted)
            throws Exception {
        return vertx.createNetServer()
                .connectHandler(socket -> {
                    connections.incrementAndGet();
                    accepted.countDown();
                    socket.close();
                })
                .listen(0, "127.0.0.1")
                .await(10, TimeUnit.SECONDS);
    }

    private DothanConfigSnapshot snapshot(int version, int proxyPort, int backendPort, String accessRule)
            throws Exception {
        Path path = temporaryDirectory.resolve("access-" + version + "-" + System.nanoTime() + ".config");
        String ruleLine = accessRule.isBlank() ? "" : accessRule + System.lineSeparator();
        Files.writeString(path, "# Dothan Config Version %d%n%s%d:127.0.0.1:%d%n"
                .formatted(version, ruleLine, proxyPort, backendPort));
        return DothanConfigParser.parse(path, false);
    }

    private void connectAndClose(NetClient client, int proxyPort) throws Exception {
        NetSocket socket = client.connect(proxyPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
        socket.close().await(10, TimeUnit.SECONDS);
    }

    private boolean waitForConnections(AtomicInteger connections, int expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (connections.get() >= expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return connections.get() >= expected;
    }

    private int availablePort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private void close(DothanRuntime runtime, NetClient client, NetServer backend, Vertx vertx)
            throws Exception {
        if (runtime != null) {
            runtime.close().await(10, TimeUnit.SECONDS);
        }
        if (client != null) {
            client.close().await(10, TimeUnit.SECONDS);
        }
        if (backend != null) {
            backend.close().await(10, TimeUnit.SECONDS);
        }
        vertx.close().await(10, TimeUnit.SECONDS);
    }
}
