package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigParser;
import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.ServerSSLOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PfxOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsTransportIntegrationTest {
    private static final String PASSWORD = "test-password";

    @TempDir
    Path temporaryDirectory;

    @Test
    void tls13MutualAuthenticationProtectsBidirectionalLargePayloads() throws Exception {
        TestKeyStores stores = TestKeyStores.create(temporaryDirectory);
        ServerSSLOptions serverSslOptions = TlsTransportOptions.server(loadTlsConfig(
                DothanTransferModeEnum.DECRYPT, stores.serverIdentity(), stores.trustStore()));
        NetServerOptions serverOptions = new NetServerOptions()
                .setSsl(true)
                .setKeyCertOptions(serverSslOptions.getKeyCertOptions())
                .setTrustOptions(serverSslOptions.getTrustOptions())
                .setClientAuth(serverSslOptions.getClientAuth())
                .setEnabledSecureTransportProtocols(serverSslOptions.getEnabledSecureTransportProtocols());
        NetClientOptions clientOptions = TlsTransportOptions.client(loadTlsConfig(
                DothanTransferModeEnum.ENCRYPT, stores.clientIdentity(), stores.trustStore()));

        Vertx vertx = Vertx.vertx();
        NetServer server = null;
        NetClient client = null;
        NetSocket socket = null;
        try {
            server = assertDoesNotThrow(() -> vertx.createNetServer(serverOptions)
                    .connectHandler(peer -> peer.handler(peer::write))
                    .exceptionHandler(ignored -> {
                    })
                    .listen(0, "127.0.0.1")
                    .await(10, TimeUnit.SECONDS), "TLS server should listen");
            NetServer listeningServer = server;
            client = vertx.createNetClient(clientOptions);
            NetClient connectingClient = client;
            socket = assertDoesNotThrow(() -> connectingClient
                    .connect(listeningServer.actualPort(), "localhost")
                    .await(10, TimeUnit.SECONDS), "mTLS client should connect");

            assertTrue(socket.isSsl());
            byte[] payload = new byte[1_000_003];
            new Random(42).nextBytes(payload);
            ByteArrayOutputStream echoed = new ByteArrayOutputStream();
            CountDownLatch complete = new CountDownLatch(1);
            socket.handler(buffer -> {
                echoed.writeBytes(buffer.getBytes());
                if (echoed.size() == payload.length) {
                    complete.countDown();
                }
            });
            NetSocket connectedSocket = socket;
            assertDoesNotThrow(() -> connectedSocket.write(Buffer.buffer(payload)).await(10, TimeUnit.SECONDS),
                    "large TLS write should complete");
            assertTrue(complete.await(10, TimeUnit.SECONDS));
            assertArrayEquals(payload, echoed.toByteArray());

            NetClientOptions missingIdentity = new NetClientOptions()
                    .setSsl(true)
                    .setTrustOptions(new PfxOptions().setPath(stores.trustStore().toString()).setPassword(PASSWORD))
                    .setTrustAll(false)
                    .setHostnameVerificationAlgorithm("HTTPS")
                    .setEnabledSecureTransportProtocols(Set.of("TLSv1.3"));
            NetClient unauthenticatedClient = vertx.createNetClient(missingIdentity);
            try {
                NetSocket unauthenticatedSocket = unauthenticatedClient
                        .connect(listeningServer.actualPort(), "localhost")
                        .await(10, TimeUnit.SECONDS);
                CountDownLatch rejected = new CountDownLatch(1);
                unauthenticatedSocket.exceptionHandler(ignored -> rejected.countDown());
                unauthenticatedSocket.closeHandler(ignored -> rejected.countDown());
                unauthenticatedSocket.write("trigger TLS handshake completion");
                assertTrue(rejected.await(10, TimeUnit.SECONDS),
                        "server must reject a peer without a client certificate");
            } finally {
                unauthenticatedClient.close();
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
            if (server != null) {
                server.close();
            }
            if (client != null) {
                client.close();
            }
            vertx.close();
        }
    }

    @Test
    void tlsSnapshotsWorkThroughStableRuntimeListeners() throws Exception {
        TestKeyStores stores = TestKeyStores.create(temporaryDirectory);
        Vertx vertx = Vertx.vertx();
        NetServer echoServer = null;
        DothanRuntime decryptRuntime = null;
        DothanRuntime encryptRuntime = null;
        NetClient client = null;
        NetSocket socket = null;
        try {
            echoServer = vertx.createNetServer()
                    .connectHandler(peer -> peer.handler(peer::write))
                    .listen(0, "127.0.0.1")
                    .await(10, TimeUnit.SECONDS);
            int decryptPort = availablePort();
            int encryptPort = availablePort();
            DothanConfigSnapshot decryptSnapshot = loadTlsConfig(
                    DothanTransferModeEnum.DECRYPT, stores.serverIdentity(), stores.trustStore(),
                    decryptPort, "127.0.0.1", echoServer.actualPort());
            DothanConfigSnapshot encryptSnapshot = loadTlsConfig(
                    DothanTransferModeEnum.ENCRYPT, stores.clientIdentity(), stores.trustStore(),
                    encryptPort, "localhost", decryptPort);

            decryptRuntime = new DothanRuntime(vertx, new DothanConfigManager());
            encryptRuntime = new DothanRuntime(vertx, new DothanConfigManager());
            decryptRuntime.start(decryptSnapshot).await(10, TimeUnit.SECONDS);
            encryptRuntime.start(encryptSnapshot).await(10, TimeUnit.SECONDS);

            client = vertx.createNetClient();
            socket = client.connect(encryptPort, "127.0.0.1").await(10, TimeUnit.SECONDS);
            byte[] payload = new byte[128_003];
            new Random(84).nextBytes(payload);
            ByteArrayOutputStream echoed = new ByteArrayOutputStream();
            CountDownLatch complete = new CountDownLatch(1);
            socket.handler(buffer -> {
                echoed.writeBytes(buffer.getBytes());
                if (echoed.size() == payload.length) {
                    complete.countDown();
                }
            });
            socket.write(Buffer.buffer(payload)).await(10, TimeUnit.SECONDS);
            assertTrue(complete.await(10, TimeUnit.SECONDS));
            assertArrayEquals(payload, echoed.toByteArray());
        } finally {
            if (socket != null) {
                socket.close().await(10, TimeUnit.SECONDS);
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
            if (echoServer != null) {
                echoServer.close().await(10, TimeUnit.SECONDS);
            }
            vertx.close().await(10, TimeUnit.SECONDS);
        }
    }

    private DothanConfigSnapshot loadTlsConfig(DothanTransferModeEnum role, Path identity, Path trust) throws Exception {
        return loadTlsConfig(role, identity, trust, 20001, "localhost", 20002);
    }

    private DothanConfigSnapshot loadTlsConfig(DothanTransferModeEnum role, Path identity, Path trust,
                                               int listenPort, String targetHost, int targetPort) throws Exception {
        Path path = temporaryDirectory.resolve(role.name().toLowerCase() + ".config");
        Files.writeString(path, """
                # MODE %s
                # SECURE TRANSPORT TLS
                # TLS KEYSTORE PATH %s
                # TLS KEYSTORE PASSWORD %s
                # TLS TRUSTSTORE PATH %s
                # TLS TRUSTSTORE PASSWORD %s
                %d:%s:%d
                """.formatted(role, identity, PASSWORD, trust, PASSWORD,
                listenPort, targetHost, targetPort));
        return DothanConfigParser.parse(path, false);
    }

    private int availablePort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private record TestKeyStores(Path serverIdentity, Path clientIdentity, Path trustStore) {
        static TestKeyStores create(Path directory) throws Exception {
            Path caIdentity = directory.resolve("ca.p12");
            Path caCertificate = directory.resolve("ca.pem");
            Path trustStore = directory.resolve("trust.p12");
            Path serverIdentity = directory.resolve("server.p12");
            Path clientIdentity = directory.resolve("client.p12");

            keytool(directory, "-genkeypair", "-alias", "ca", "-dname", "CN=Dothan Test CA",
                    "-ext", "bc=ca:true", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-keystore", caIdentity.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD,
                    "-keypass", PASSWORD, "-noprompt");
            keytool(directory, "-exportcert", "-rfc", "-alias", "ca", "-keystore", caIdentity.toString(),
                    "-storepass", PASSWORD, "-file", caCertificate.toString());
            keytool(directory, "-importcert", "-alias", "ca", "-file", caCertificate.toString(),
                    "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD,
                    "-noprompt");
            signedIdentity(directory, caIdentity, caCertificate, serverIdentity, "server", "CN=localhost",
                    "SAN=dns:localhost,ip:127.0.0.1", "EKU=serverAuth");
            signedIdentity(directory, caIdentity, caCertificate, clientIdentity, "client", "CN=dothan-client",
                    "SAN=dns:dothan-client", "EKU=clientAuth");
            return new TestKeyStores(serverIdentity, clientIdentity, trustStore);
        }

        private static void signedIdentity(Path directory, Path caIdentity, Path caCertificate, Path identity,
                                           String alias, String distinguishedName, String san, String eku)
                throws Exception {
            Path request = directory.resolve(alias + ".csr");
            Path certificate = directory.resolve(alias + ".pem");
            keytool(directory, "-genkeypair", "-alias", alias, "-dname", distinguishedName,
                    "-ext", san, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-keystore", identity.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD,
                    "-keypass", PASSWORD, "-noprompt");
            keytool(directory, "-certreq", "-alias", alias, "-keystore", identity.toString(),
                    "-storepass", PASSWORD, "-file", request.toString());
            keytool(directory, "-gencert", "-rfc", "-alias", "ca", "-keystore", caIdentity.toString(),
                    "-storepass", PASSWORD, "-infile", request.toString(), "-outfile", certificate.toString(),
                    "-validity", "2", "-ext", san, "-ext", "KU=digitalSignature,keyEncipherment", "-ext", eku);
            keytool(directory, "-importcert", "-alias", "ca", "-file", caCertificate.toString(),
                    "-keystore", identity.toString(), "-storepass", PASSWORD, "-noprompt");
            keytool(directory, "-importcert", "-alias", alias, "-file", certificate.toString(),
                    "-keystore", identity.toString(), "-storepass", PASSWORD, "-noprompt");
        }

        private static void keytool(Path directory, String... arguments) throws Exception {
            String executable = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            String[] command = new String[arguments.length + 1];
            command[0] = executable;
            System.arraycopy(arguments, 0, command, 1, arguments.length);
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
        }
    }
}
