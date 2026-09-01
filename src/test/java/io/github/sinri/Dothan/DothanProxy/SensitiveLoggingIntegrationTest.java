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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveLoggingIntegrationTest {
    private static final String TRANSFER_KEY = "known-transfer-key-must-not-be-logged";
    private static final String KEYSTORE_PASSWORD = "known-keystore-password-must-not-be-logged";
    private static final String TRUSTSTORE_PASSWORD = "known-truststore-password-must-not-be-logged";
    private static final String REQUEST_PAYLOAD = "known-client-password-and-query-must-not-be-logged";
    private static final String RESPONSE_PAYLOAD = "known-service-response-must-not-be-logged";

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(60)
    void verboseLoggingIncludesTransferMetadataButNeverCredentialsOrPayloads() throws Exception {
        io.vertx.core.internal.logging.LoggerFactory.getLogger(SensitiveLoggingIntegrationTest.class);
        Logger rootLogger = Logger.getLogger("");
        Handler[] existingHandlers = rootLogger.getHandlers();
        Level[] previousHandlerLevels = new Level[existingHandlers.length];
        for (int index = 0; index < existingHandlers.length; index++) {
            previousHandlerLevels[index] = existingHandlers[index].getLevel();
            existingHandlers[index].setLevel(Level.OFF);
        }
        Level previousLevel = rootLogger.getLevel();
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        rootLogger.setLevel(Level.ALL);
        rootLogger.addHandler(handler);

        Vertx vertx = null;
        NetServer backend = null;
        NetClient client = null;
        NetSocket connection = null;
        DothanRuntime runtime = null;
        try {
            parseSensitiveConfigurations();

            vertx = Vertx.vertx();
            backend = vertx.createNetServer()
                    .connectHandler(socket -> socket.handler(ignored -> socket.write(RESPONSE_PAYLOAD)))
                    .listen(0, "127.0.0.1")
                    .await(10, TimeUnit.SECONDS);

            int proxyPort = availablePort();
            DothanConfigSnapshot snapshot = plainSnapshot(proxyPort, backend.actualPort());
            runtime = new DothanRuntime(vertx, new DothanConfigManager());
            runtime.start(snapshot).await(10, TimeUnit.SECONDS);

            CountDownLatch responseReceived = new CountDownLatch(1);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            client = vertx.createNetClient();
            connection = client.connect(proxyPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
            connection.handler(buffer -> {
                response.writeBytes(buffer.getBytes());
                responseReceived.countDown();
            });
            connection.write(REQUEST_PAYLOAD).await(10, TimeUnit.SECONDS);

            assertTrue(responseReceived.await(10, TimeUnit.SECONDS), "timed out waiting for proxied response");
            assertEquals(RESPONSE_PAYLOAD, response.toString(StandardCharsets.UTF_8));

            String logs = handler.contents();
            assertTrue(logs.contains("Forwarded " + REQUEST_PAYLOAD.getBytes(StandardCharsets.UTF_8).length
                    + " plaintext bytes in request direction"));
            assertTrue(logs.contains("Forwarded " + RESPONSE_PAYLOAD.getBytes(StandardCharsets.UTF_8).length
                    + " plaintext bytes in response direction"));
            assertSensitiveValueAbsent(logs, TRANSFER_KEY);
            assertSensitiveValueAbsent(logs, KEYSTORE_PASSWORD);
            assertSensitiveValueAbsent(logs, TRUSTSTORE_PASSWORD);
            assertSensitiveValueAbsent(logs, REQUEST_PAYLOAD);
            assertSensitiveValueAbsent(logs, RESPONSE_PAYLOAD);
        } finally {
            if (connection != null) {
                connection.close().await(10, TimeUnit.SECONDS);
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
            if (vertx != null) {
                vertx.close().await(10, TimeUnit.SECONDS);
            }
            rootLogger.removeHandler(handler);
            rootLogger.setLevel(previousLevel);
            for (int index = 0; index < existingHandlers.length; index++) {
                existingHandlers[index].setLevel(previousHandlerLevels[index]);
            }
        }
    }

    private void parseSensitiveConfigurations() throws Exception {
        Path recordConfig = temporaryDirectory.resolve("record.config");
        Files.writeString(recordConfig, """
                # Dothan Config Version 1
                # MODE ENCRYPT
                # SECURE TRANSPORT RECORD
                # TRANSFER KEY %s
                20001:record.example:3306
                """.formatted(TRANSFER_KEY));
        DothanConfigParser.parse(recordConfig, true);

        Path tlsConfig = temporaryDirectory.resolve("tls.config");
        Files.writeString(tlsConfig, """
                # Dothan Config Version 2
                # MODE ENCRYPT
                # SECURE TRANSPORT TLS
                # TLS KEYSTORE PATH identity.p12
                # TLS KEYSTORE PASSWORD %s
                # TLS TRUSTSTORE PATH trust.p12
                # TLS TRUSTSTORE PASSWORD %s
                20002:tls.example:3306
                """.formatted(KEYSTORE_PASSWORD, TRUSTSTORE_PASSWORD));
        DothanConfigParser.parse(tlsConfig, true);
    }

    private DothanConfigSnapshot plainSnapshot(int listenPort, int backendPort) throws Exception {
        Path path = temporaryDirectory.resolve("plain.config");
        Files.writeString(path, """
                # Dothan Config Version 3
                %d:127.0.0.1:%d
                """.formatted(listenPort, backendPort));
        return DothanConfigParser.parse(path, true);
    }

    private int availablePort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private void assertSensitiveValueAbsent(String logs, String value) {
        assertFalse(logs.contains(value), () -> "sensitive value was written to logs: " + value);
    }

    private static final class CapturingHandler extends Handler {
        private final StringBuilder records = new StringBuilder();

        @Override
        public synchronized void publish(LogRecord record) {
            if (isLoggable(record)) {
                records.append(record.getLevel()).append(' ')
                        .append(record.getMessage()).append('\n');
                if (record.getThrown() != null) {
                    records.append(record.getThrown()).append('\n');
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private synchronized String contents() {
            return records.toString();
        }
    }
}
