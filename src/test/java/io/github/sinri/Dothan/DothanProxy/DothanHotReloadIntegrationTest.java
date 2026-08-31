package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigParser;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DothanHotReloadIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(60)
    void existingConnectionKeepsItsSnapshotWhileNewConnectionUsesPublishedSnapshot() throws Exception {
        Vertx vertx = Vertx.vertx();
        NetServer oldBackend = null;
        NetServer newBackend = null;
        NetClient client = null;
        NetSocket existingConnection = null;
        NetSocket newConnection = null;
        DothanRuntime runtime = null;
        try {
            oldBackend = backend(vertx, "old:");
            newBackend = backend(vertx, "new:");
            int proxyPort = availablePort();
            DothanConfigSnapshot initial = snapshot(1, proxyPort, oldBackend.actualPort());
            DothanConfigManager manager = new DothanConfigManager();
            runtime = new DothanRuntime(vertx, manager);
            runtime.start(initial).await(10, TimeUnit.SECONDS);

            client = vertx.createNetClient();
            existingConnection = client.connect(proxyPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
            assertEquals("old:first", exchange(existingConnection, "first", "old:"));

            DothanConfigSnapshot replacement = snapshot(2, proxyPort, newBackend.actualPort());
            runtime.reload(replacement).await(10, TimeUnit.SECONDS);

            assertEquals("old:second", exchange(existingConnection, "second", "old:"));
            newConnection = client.connect(proxyPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
            assertEquals("new:third", exchange(newConnection, "third", "new:"));
        } finally {
            if (existingConnection != null) {
                existingConnection.close().await(10, TimeUnit.SECONDS);
            }
            if (newConnection != null) {
                newConnection.close().await(10, TimeUnit.SECONDS);
            }
            if (runtime != null) {
                runtime.close().await(10, TimeUnit.SECONDS);
            }
            if (client != null) {
                client.close().await(10, TimeUnit.SECONDS);
            }
            if (oldBackend != null) {
                oldBackend.close().await(10, TimeUnit.SECONDS);
            }
            if (newBackend != null) {
                newBackend.close().await(10, TimeUnit.SECONDS);
            }
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }

    private NetServer backend(Vertx vertx, String prefix) throws Exception {
        return vertx.createNetServer()
                .connectHandler(socket -> socket.handler(buffer ->
                        socket.write(Buffer.buffer(prefix).appendBuffer(buffer))))
                .listen(0, "127.0.0.1")
                .await(10, TimeUnit.SECONDS);
    }

    private String exchange(NetSocket socket, String request, String expectedPrefix) throws Exception {
        byte[] expected = expectedPrefix.concat(request).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        CountDownLatch complete = new CountDownLatch(1);
        socket.handler(buffer -> {
            response.writeBytes(buffer.getBytes());
            if (response.size() >= expected.length) {
                complete.countDown();
            }
        });
        socket.write(request).await(10, TimeUnit.SECONDS);
        assertTrue(complete.await(10, TimeUnit.SECONDS), "timed out waiting for proxied response");
        return response.toString(StandardCharsets.UTF_8);
    }

    private DothanConfigSnapshot snapshot(int version, int listenPort, int backendPort) throws Exception {
        Path path = temporaryDirectory.resolve("integration-" + version + ".config");
        Files.writeString(path, """
                # Dothan Config Version %d
                %d:127.0.0.1:%d
                """.formatted(version, listenPort, backendPort));
        return DothanConfigParser.parse(path, false);
    }

    private int availablePort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}
