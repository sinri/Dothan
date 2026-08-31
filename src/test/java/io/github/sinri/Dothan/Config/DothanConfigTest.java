package io.github.sinri.Dothan.Config;

import io.github.sinri.Dothan.DothanProxy.DothanTransferModeEnum;
import io.github.sinri.Dothan.DothanProxy.SecureTransportModeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DothanConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void existingEncryptedConfigurationDefaultsToSecureRecords() throws Exception {
        DothanConfigSnapshot config = parse("""
                # Dothan Config Version 8
                # MODE ENCRYPT
                # TRANSFER KEY a-long-random-shared-secret
                20001:remote.example:20002
                """);

        assertEquals(8, config.getVersion());
        assertEquals(DothanTransferModeEnum.ENCRYPT, config.getTransferMode());
        assertEquals(SecureTransportModeEnum.RECORD, config.getSecureTransportMode());
        assertEquals("a-long-random-shared-secret", config.getTransferKey());
    }

    @Test
    void readsTlsConfiguration() throws Exception {
        DothanConfigSnapshot config = parse("""
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
    void deletingOptionalRulesProducesDefaultsInsteadOfRetainingOldValues() throws Exception {
        DothanConfigSnapshot oldConfig = parse("""
                # Dothan Config Version 1
                + 127.0.0.1
                - 192.0.2.1
                # MODE ENCRYPT
                # TRANSFER KEY random-secret
                20001:remote.example:20002
                """);
        DothanConfigSnapshot newConfig = parse("""
                # Dothan Config Version 2
                20001:remote.example:20002
                """);

        assertFalse(oldConfig.getWhitelist().isEmpty());
        assertFalse(oldConfig.getBlacklist().isEmpty());
        assertTrue(newConfig.getWhitelist().isEmpty());
        assertTrue(newConfig.getBlacklist().isEmpty());
        assertEquals(DothanTransferModeEnum.PLAIN, newConfig.getTransferMode());
        assertEquals("", newConfig.getTransferKey());
    }

    @Test
    void rejectsPartialInvalidAndConflictingConfiguration() {
        assertInvalid("# MODE ENCRYPT\n20001:remote.example:20002\n");
        assertInvalid("# MODE DECRYPT\n# SECURE TRANSPORT TLS\n# TLS KEYSTORE PATH /x\n20001:host:2\n");
        assertInvalid("20001:host.example:3306\n20001:other.example:3307\n");
        assertInvalid("0:host.example:3306\n");
        assertInvalid("20001:host.example:65536\n");
        assertInvalid("20001:not_a_host:3306\n");
        assertInvalid("+ not-an-ip\n20001:host.example:3306\n");
        assertInvalid("+ 127.0.0.1\n- 127.0.0.1\n20001:host.example:3306\n");
        assertInvalid("# MODE PLAIN\n# TRANSFER KEY unused\n20001:host.example:3306\n");
        assertInvalid("# MODE PLAIN\n# MODE ENCRYPT\n20001:host.example:3306\n");
        assertInvalid("this file is only partially written");
    }

    @Test
    void snapshotCollectionsAndRoutesAreImmutable() throws Exception {
        DothanConfigSnapshot snapshot = parse("""
                + 127.0.0.1
                20001:host.example:3306
                """);

        assertThrows(UnsupportedOperationException.class, () -> snapshot.getWhitelist().add("192.0.2.1"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getListenPorts().remove(20001));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getDothanProxyRequirements().clear());
    }

    @Test
    void invalidCandidateCannotModifyPublishedSnapshot() throws Exception {
        DothanConfigManager manager = new DothanConfigManager();
        DothanConfigSnapshot active = parse("""
                # Dothan Config Version 5
                + 127.0.0.1
                20001:host.example:3306
                """);
        assertTrue(manager.initialize(active));

        assertInvalid("""
                # Dothan Config Version 6
                - broken-address
                20002:new.example:3306
                """);

        assertEquals(5, manager.current().getVersion());
        assertTrue(manager.current().getWhitelist().contains("127.0.0.1"));
        assertTrue(manager.current().findRequirement(20001).isPresent());
    }

    private void assertInvalid(String content) {
        assertThrows(Exception.class, () -> parse(content));
    }

    private DothanConfigSnapshot parse(String content) throws Exception {
        Path path = temporaryDirectory.resolve("dothan-" + System.nanoTime() + ".config");
        Files.writeString(path, content);
        return DothanConfigParser.parse(path, false);
    }
}
