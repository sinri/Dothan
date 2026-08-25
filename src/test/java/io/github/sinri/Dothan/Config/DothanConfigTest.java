package io.github.sinri.Dothan.Config;

import io.github.sinri.Dothan.DothanProxy.DothanTransferModeEnum;
import io.github.sinri.Dothan.DothanProxy.SecureTransportModeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DothanConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void existingEncryptedConfigurationDefaultsToSecureRecords() throws Exception {
        DothanConfig config = load("""
                # Dothan Config Version 8
                # MODE ENCRYPT
                # TRANSFER KEY a-long-random-shared-secret
                20001:remote.example:20002
                """);

        assertEquals(DothanTransferModeEnum.ENCRYPT, config.getTransferMode());
        assertEquals(SecureTransportModeEnum.RECORD, config.getSecureTransportMode());
        assertEquals("a-long-random-shared-secret", config.getTransferKey());
    }

    @Test
    void readsTlsConfiguration() throws Exception {
        DothanConfig config = load("""
                # MODE DECRYPT
                # SECURE TRANSPORT TLS
                # TLS KEYSTORE PATH /secure/server.p12
                # TLS KEYSTORE PASSWORD identity-password
                # TLS TRUSTSTORE PATH /secure/ca.p12
                # TLS TRUSTSTORE PASSWORD trust-password
                20002:127.0.0.1:3306
                """);

        assertEquals(SecureTransportModeEnum.TLS, config.getSecureTransportMode());
        assertEquals("/secure/server.p12", config.getTlsKeyStorePath());
        assertEquals("identity-password", config.getTlsKeyStorePassword());
        assertEquals("/secure/ca.p12", config.getTlsTrustStorePath());
        assertEquals("trust-password", config.getTlsTrustStorePassword());
    }

    @Test
    void rejectsIncompleteSecureConfiguration() throws Exception {
        Path recordConfig = write("""
                # MODE ENCRYPT
                20001:remote.example:20002
                """);
        DothanConfig.getInstance().setConfigFilePath(recordConfig.toString());
        assertThrows(IOException.class, () -> DothanConfig.getInstance().loadFromConfigFile());

        Path tlsConfig = write("""
                # MODE DECRYPT
                # SECURE TRANSPORT TLS
                # TLS KEYSTORE PATH /secure/server.p12
                20002:127.0.0.1:3306
                """);
        DothanConfig.getInstance().setConfigFilePath(tlsConfig.toString());
        assertThrows(IOException.class, () -> DothanConfig.getInstance().loadFromConfigFile());
    }

    private DothanConfig load(String content) throws Exception {
        Path path = write(content);
        DothanConfig config = DothanConfig.getInstance();
        config.setConfigFilePath(path.toString());
        config.loadFromConfigFile();
        return config;
    }

    private Path write(String content) throws Exception {
        Path path = temporaryDirectory.resolve("dothan-" + System.nanoTime() + ".config");
        return Files.writeString(path, content);
    }
}
