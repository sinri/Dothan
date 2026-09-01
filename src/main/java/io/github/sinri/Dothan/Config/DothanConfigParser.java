package io.github.sinri.Dothan.Config;

import io.github.sinri.Dothan.DothanProxy.ClientAccessPolicy;
import io.github.sinri.Dothan.DothanProxy.DothanProxyRequirement;
import io.github.sinri.Dothan.DothanProxy.DothanTransferModeEnum;
import io.github.sinri.Dothan.DothanProxy.SecureTransportModeEnum;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.validator.routines.InetAddressValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses and validates configuration without changing the active runtime state.
 */
public final class DothanConfigParser {
    private DothanConfigParser() {
    }

    public static DothanConfigSnapshot parse(Path path, boolean verbose)
            throws IOException, DothanConfigException {
        List<String> lines = Files.readAllLines(path);
        Builder builder = new Builder(verbose);
        for (int index = 0; index < lines.size(); index++) {
            builder.accept(lines.get(index), index + 1);
        }
        return builder.build();
    }

    public static DothanConfigSnapshot parse(CommandLine options, boolean verbose)
            throws DothanConfigException {
        Builder builder = new Builder(verbose);
        builder.version = -1;
        String host = options.getOptionValue("h", "127.0.0.1");
        if (!validHost(host)) {
            throw new DothanConfigException("invalid server host: " + host);
        }
        int serverPort = parsePort(options.getOptionValue("p", "3306"), "server port");
        int listenPort = parsePort(options.getOptionValue("l", "20001"), "listen port");
        builder.requirements.add(new DothanProxyRequirement(host, serverPort, listenPort));
        addCommandLineAddresses(options.getOptionValue("w"), builder.whitelist, "whitelist");
        addCommandLineAddresses(options.getOptionValue("b"), builder.blacklist, "blacklist");
        return builder.build();
    }

    private static void addCommandLineAddresses(String value, Set<String> destination, String label)
            throws DothanConfigException {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String address : value.split(",")) {
            String trimmed = address.trim();
            if (!InetAddressValidator.getInstance().isValid(trimmed)) {
                throw new DothanConfigException("invalid " + label + " address: " + trimmed);
            }
            destination.add(ClientAccessPolicy.canonicalAddress(trimmed));
        }
    }

    private static int parsePort(String value, String label) throws DothanConfigException {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new DothanConfigException(label + " must be between 1 and 65535: " + value);
            }
            return port;
        } catch (NumberFormatException error) {
            throw new DothanConfigException("invalid " + label + ": " + value, error);
        }
    }

    private static boolean validHost(String host) {
        if (InetAddressValidator.getInstance().isValid(host)) {
            return true;
        }
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        String normalized = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        for (String label : normalized.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !Character.isLetterOrDigit(label.charAt(0))
                    || !Character.isLetterOrDigit(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int index = 1; index < label.length() - 1; index++) {
                char character = label.charAt(index);
                if (!Character.isLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class Builder {
        private final boolean verbose;
        private final List<DothanProxyRequirement> requirements = new ArrayList<>();
        private final Set<String> whitelist = new HashSet<>();
        private final Set<String> blacklist = new HashSet<>();
        private final Set<String> directives = new HashSet<>();
        private int version;
        private DothanTransferModeEnum transferMode = DothanTransferModeEnum.PLAIN;
        private SecureTransportModeEnum secureTransportMode = SecureTransportModeEnum.RECORD;
        private String transferKey = "";
        private String tlsKeyStorePath = "";
        private String tlsKeyStorePassword = "";
        private String tlsTrustStorePath = "";
        private String tlsTrustStorePassword = "";

        private Builder(boolean verbose) {
            this.verbose = verbose;
        }

        private void accept(String sourceLine, int lineNumber) throws DothanConfigException {
            String line = sourceLine.trim();
            if (line.isEmpty()) {
                return;
            }
            if (line.startsWith("# Dothan Config Version ")) {
                unique("version", lineNumber);
                String value = line.substring(24);
                try {
                    version = Integer.parseInt(value);
                    if (version < 0) {
                        throw error(lineNumber, "version must not be negative");
                    }
                } catch (NumberFormatException error) {
                    throw error(lineNumber, "invalid configuration version", error);
                }
                return;
            }
            if (line.startsWith("+ ")) {
                addAddress(whitelist, line.substring(2), "whitelist", lineNumber);
                return;
            }
            if (line.startsWith("- ")) {
                addAddress(blacklist, line.substring(2), "blacklist", lineNumber);
                return;
            }
            if (line.startsWith("# MODE ")) {
                unique("mode", lineNumber);
                try {
                    transferMode = DothanTransferModeEnum.valueOf(line.substring(7));
                } catch (IllegalArgumentException error) {
                    throw error(lineNumber, "invalid transfer mode", error);
                }
                return;
            }
            if (line.startsWith("# TRANSFER KEY ")) {
                unique("transfer-key", lineNumber);
                transferKey = line.substring(15);
                return;
            }
            if (line.startsWith("# SECURE TRANSPORT ")) {
                unique("secure-transport", lineNumber);
                try {
                    secureTransportMode = SecureTransportModeEnum.valueOf(line.substring(19));
                } catch (IllegalArgumentException error) {
                    throw error(lineNumber, "invalid secure transport", error);
                }
                return;
            }
            if (line.startsWith("# TLS KEYSTORE PATH ")) {
                unique("tls-keystore-path", lineNumber);
                tlsKeyStorePath = line.substring(20);
                return;
            }
            if (line.startsWith("# TLS KEYSTORE PASSWORD ")) {
                unique("tls-keystore-password", lineNumber);
                tlsKeyStorePassword = line.substring(24);
                return;
            }
            if (line.startsWith("# TLS TRUSTSTORE PATH ")) {
                unique("tls-truststore-path", lineNumber);
                tlsTrustStorePath = line.substring(22);
                return;
            }
            if (line.startsWith("# TLS TRUSTSTORE PASSWORD ")) {
                unique("tls-truststore-password", lineNumber);
                tlsTrustStorePassword = line.substring(26);
                return;
            }
            if (line.startsWith("#")) {
                return;
            }
            requirements.add(parseRequirement(line, lineNumber));
        }

        private DothanProxyRequirement parseRequirement(String line, int lineNumber)
                throws DothanConfigException {
            String listen;
            String hostAndPort;
            int whitespace = firstWhitespace(line);
            if (whitespace >= 0) {
                listen = line.substring(0, whitespace);
                hostAndPort = line.substring(whitespace).trim();
            } else {
                int separator = line.indexOf(':');
                if (separator < 0) {
                    throw error(lineNumber, "invalid proxy requirement");
                }
                listen = line.substring(0, separator);
                hostAndPort = line.substring(separator + 1);
            }

            int targetSeparator = hostAndPort.lastIndexOf(':');
            if (targetSeparator <= 0 || targetSeparator == hostAndPort.length() - 1) {
                throw error(lineNumber, "invalid proxy target");
            }
            String host = hostAndPort.substring(0, targetSeparator);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            if (!validHost(host)) {
                throw error(lineNumber, "invalid proxy target host: " + host);
            }
            return new DothanProxyRequirement(
                    host,
                    parsePort(hostAndPort.substring(targetSeparator + 1), "server port at line " + lineNumber),
                    parsePort(listen, "listen port at line " + lineNumber));
        }

        private DothanConfigSnapshot build() throws DothanConfigException {
            if (requirements.isEmpty()) {
                throw new DothanConfigException("at least one proxy requirement is required");
            }
            Set<Integer> listenPorts = new HashSet<>();
            for (DothanProxyRequirement requirement : requirements) {
                if (!listenPorts.add(requirement.listenPort)) {
                    throw new DothanConfigException("duplicate listen port: " + requirement.listenPort);
                }
            }
            Set<String> overlap = new HashSet<>(whitelist);
            overlap.retainAll(blacklist);
            if (!overlap.isEmpty()) {
                throw new DothanConfigException("addresses cannot be both whitelisted and blacklisted: " + overlap);
            }
            validateSecurity();
            return new DothanConfigSnapshot(version, requirements, whitelist, blacklist,
                    transferMode, secureTransportMode, transferKey,
                    tlsKeyStorePath, tlsKeyStorePassword, tlsTrustStorePath, tlsTrustStorePassword, verbose);
        }

        private void validateSecurity() throws DothanConfigException {
            boolean hasTls = !tlsKeyStorePath.isBlank() || !tlsKeyStorePassword.isBlank()
                    || !tlsTrustStorePath.isBlank() || !tlsTrustStorePassword.isBlank();
            if (transferMode == DothanTransferModeEnum.PLAIN) {
                if (!transferKey.isBlank() || hasTls || directives.contains("secure-transport")) {
                    throw new DothanConfigException("PLAIN mode must not define secure transport credentials");
                }
                return;
            }
            if (secureTransportMode == SecureTransportModeEnum.RECORD) {
                if (transferKey.isBlank()) {
                    throw new DothanConfigException("TRANSFER KEY is required for RECORD secure transport");
                }
                if (hasTls) {
                    throw new DothanConfigException("RECORD secure transport must not define TLS credentials");
                }
                return;
            }
            if (!transferKey.isBlank()) {
                throw new DothanConfigException("TLS secure transport must not define TRANSFER KEY");
            }
            if (tlsKeyStorePath.isBlank() || tlsKeyStorePassword.isBlank()
                    || tlsTrustStorePath.isBlank() || tlsTrustStorePassword.isBlank()) {
                throw new DothanConfigException(
                        "TLS secure transport requires keystore and truststore paths and passwords");
            }
        }

        private void unique(String directive, int lineNumber) throws DothanConfigException {
            if (!directives.add(directive)) {
                throw error(lineNumber, "duplicate directive: " + directive);
            }
        }

        private void addAddress(Set<String> target, String value, String label, int lineNumber)
                throws DothanConfigException {
            if (!InetAddressValidator.getInstance().isValid(value)) {
                throw error(lineNumber, "invalid " + label + " address: " + value);
            }
            target.add(ClientAccessPolicy.canonicalAddress(value));
        }

        private static int firstWhitespace(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isWhitespace(value.charAt(index))) {
                    return index;
                }
            }
            return -1;
        }

        private DothanConfigException error(int lineNumber, String message) {
            return new DothanConfigException("line " + lineNumber + ": " + message);
        }

        private DothanConfigException error(int lineNumber, String message, Throwable cause) {
            return new DothanConfigException("line " + lineNumber + ": " + message, cause);
        }
    }
}
