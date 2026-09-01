package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigParser;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.Future;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DothanBackpressureIntegrationTest {
    private static final String TRANSFER_KEY = "backpressure-test-key-with-enough-entropy";

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(60)
    void preservesLargeBurstsAcrossSlowBackendAndSlowClientAndPropagatesClose() throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicReference<NetSocket> backendPeer = new AtomicReference<>();
        CountDownLatch backendAccepted = new CountDownLatch(1);
        AtomicInteger backendBytes = new AtomicInteger();
        NetServer backend = null;
        DothanRuntime runtime = null;
        NetClient client = null;
        NetSocket clientSocket = null;
        try {
            backend = vertx.createNetServer().connectHandler(peer -> {
                peer.pause();
                peer.handler(buffer -> {
                    backendBytes.addAndGet(buffer.length());
                    peer.write(buffer);
                });
                backendPeer.set(peer);
                backendAccepted.countDown();
            }).listen(0, "127.0.0.1").await(10, TimeUnit.SECONDS);
            int proxyPort = availablePort();
            runtime = new DothanRuntime(vertx, new DothanConfigManager());
            runtime.start(snapshot(proxyPort, backend.actualPort())).await(10, TimeUnit.SECONDS);
            client = vertx.createNetClient();
            clientSocket = client.connect(proxyPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
            assertTrue(backendAccepted.await(10, TimeUnit.SECONDS));

            byte[] request = randomBytes(2 * 1024 * 1024 + 17, 91);
            ByteArrayOutputStream echoed = new ByteArrayOutputStream();
            CountDownLatch requestEchoed = new CountDownLatch(1);
            clientSocket.handler(buffer -> {
                echoed.writeBytes(buffer.getBytes());
                if (echoed.size() == request.length) {
                    requestEchoed.countDown();
                }
            });
            Future<Void> requestWrite = clientSocket.write(Buffer.buffer(request));
            Thread.sleep(150);
            assertEquals(0, backendBytes.get(), "paused backend must not consume the burst");

            backendPeer.get().resume();
            requestWrite.await(10, TimeUnit.SECONDS);
            assertTrue(requestEchoed.await(10, TimeUnit.SECONDS));
            assertArrayEquals(request, echoed.toByteArray());

            byte[] response = randomBytes(2 * 1024 * 1024 + 31, 92);
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            CountDownLatch responseReceived = new CountDownLatch(1);
            clientSocket.pause();
            clientSocket.handler(buffer -> {
                received.writeBytes(buffer.getBytes());
                if (received.size() == response.length) {
                    responseReceived.countDown();
                }
            });
            backendPeer.get().write(Buffer.buffer(response));
            Thread.sleep(150);
            assertEquals(0, received.size(), "paused client must not consume the response burst");

            clientSocket.resume();
            assertTrue(responseReceived.await(10, TimeUnit.SECONDS));
            assertArrayEquals(response, received.toByteArray());

            CountDownLatch clientClosed = new CountDownLatch(1);
            clientSocket.closeHandler(ignored -> clientClosed.countDown());
            backendPeer.get().close();
            assertTrue(clientClosed.await(10, TimeUnit.SECONDS),
                    "backend interruption must close the client side");
        } finally {
            if (clientSocket != null) {
                clientSocket.close();
            }
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

    @Test
    @Timeout(60)
    void secureRecordTransformationPreservesABurstWhileThePlaintextBackendIsSlow() throws Exception {
        Vertx vertx = Vertx.vertx();
        AtomicReference<NetSocket> backendPeer = new AtomicReference<>();
        CountDownLatch backendAccepted = new CountDownLatch(1);
        NetServer backend = null;
        DothanRuntime decryptRuntime = null;
        DothanRuntime encryptRuntime = null;
        NetClient client = null;
        NetSocket clientSocket = null;
        try {
            backend = vertx.createNetServer().connectHandler(peer -> {
                peer.pause();
                peer.handler(peer::write);
                backendPeer.set(peer);
                backendAccepted.countDown();
            }).listen(0, "127.0.0.1").await(10, TimeUnit.SECONDS);
            int decryptPort = availablePort();
            int encryptPort = availablePort();
            decryptRuntime = new DothanRuntime(vertx, new DothanConfigManager());
            encryptRuntime = new DothanRuntime(vertx, new DothanConfigManager());
            decryptRuntime.start(recordSnapshot(
                    DothanTransferModeEnum.DECRYPT, decryptPort, backend.actualPort(), 2))
                    .await(10, TimeUnit.SECONDS);
            encryptRuntime.start(recordSnapshot(
                    DothanTransferModeEnum.ENCRYPT, encryptPort, decryptPort, 3))
                    .await(10, TimeUnit.SECONDS);

            client = vertx.createNetClient();
            clientSocket = client.connect(encryptPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
            assertTrue(backendAccepted.await(10, TimeUnit.SECONDS));
            byte[] payload = randomBytes(1024 * 1024 + 113, 93);
            ByteArrayOutputStream echoed = new ByteArrayOutputStream();
            CountDownLatch complete = new CountDownLatch(1);
            clientSocket.handler(buffer -> {
                echoed.writeBytes(buffer.getBytes());
                if (echoed.size() == payload.length) {
                    complete.countDown();
                }
            });

            Future<Void> write = clientSocket.write(Buffer.buffer(payload));
            Thread.sleep(150);
            backendPeer.get().resume();
            write.await(10, TimeUnit.SECONDS);

            assertTrue(complete.await(20, TimeUnit.SECONDS));
            assertArrayEquals(payload, echoed.toByteArray());
        } finally {
            if (clientSocket != null) {
                clientSocket.close();
            }
            if (encryptRuntime != null) {
                encryptRuntime.close().await(10, TimeUnit.SECONDS);
            }
            if (decryptRuntime != null) {
                decryptRuntime.close().await(10, TimeUnit.SECONDS);
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

    private DothanConfigSnapshot snapshot(int proxyPort, int backendPort) throws Exception {
        Path path = temporaryDirectory.resolve("backpressure.config");
        Files.writeString(path, "# Dothan Config Version 1%n%d:127.0.0.1:%d%n"
                .formatted(proxyPort, backendPort));
        return DothanConfigParser.parse(path, false);
    }

    private DothanConfigSnapshot recordSnapshot(DothanTransferModeEnum role, int proxyPort,
                                                int backendPort, int version) throws Exception {
        Path path = temporaryDirectory.resolve("backpressure-record-" + role.name().toLowerCase() + ".config");
        Files.writeString(path, """
                # Dothan Config Version %d
                # MODE %s
                # SECURE TRANSPORT RECORD
                # TRANSFER KEY %s
                %d:127.0.0.1:%d
                """.formatted(version, role, TRANSFER_KEY, proxyPort, backendPort));
        return DothanConfigParser.parse(path, false);
    }

    private int availablePort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private byte[] randomBytes(int length, int seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}
