package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigParser;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.ServerSSLOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsTransportOptionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void enforcesTls13PeerVerificationAndMutualAuthentication() throws Exception {
        Path configPath = temporaryDirectory.resolve("tls.config");
        Files.writeString(configPath, """
                # MODE ENCRYPT
                # SECURE TRANSPORT TLS
                # TLS KEYSTORE PATH /secure/client.p12
                # TLS KEYSTORE PASSWORD identity-password
                # TLS TRUSTSTORE PATH /secure/ca.p12
                # TLS TRUSTSTORE PASSWORD trust-password
                20001:remote.example:20002
                """);
        DothanConfigSnapshot config = DothanConfigParser.parse(configPath, false);

        NetClientOptions client = TlsTransportOptions.client(config);
        ServerSSLOptions server = TlsTransportOptions.server(config);

        assertTrue(client.isSsl());
        assertFalse(client.isTrustAll());
        assertEquals("HTTPS", client.getHostnameVerificationAlgorithm());
        assertEquals(Set.of("TLSv1.3"), client.getEnabledSecureTransportProtocols());
        assertEquals(ClientAuth.REQUIRED, server.getClientAuth());
        assertEquals(Set.of("TLSv1.3"), server.getEnabledSecureTransportProtocols());
    }
}
