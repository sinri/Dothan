package io.github.sinri.Dothan.Config;

import io.github.sinri.Dothan.DothanProxy.DothanProxyRequirement;
import io.github.sinri.Dothan.DothanProxy.DothanTransferModeEnum;
import io.github.sinri.Dothan.DothanProxy.SecureTransportModeEnum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable, fully validated configuration generation.
 */
public final class DothanConfigSnapshot {
    private final int version;
    private final Map<Integer, DothanProxyRequirement> requirementsByListenPort;
    private final Set<String> whitelist;
    private final Set<String> blacklist;
    private final DothanTransferModeEnum transferMode;
    private final SecureTransportModeEnum secureTransportMode;
    private final String transferKey;
    private final String tlsKeyStorePath;
    private final String tlsKeyStorePassword;
    private final String tlsTrustStorePath;
    private final String tlsTrustStorePassword;
    private final boolean verbose;

    DothanConfigSnapshot(int version,
                         List<DothanProxyRequirement> requirements,
                         Set<String> whitelist,
                         Set<String> blacklist,
                         DothanTransferModeEnum transferMode,
                         SecureTransportModeEnum secureTransportMode,
                         String transferKey,
                         String tlsKeyStorePath,
                         String tlsKeyStorePassword,
                         String tlsTrustStorePath,
                         String tlsTrustStorePassword,
                         boolean verbose) {
        this.version = version;
        LinkedHashMap<Integer, DothanProxyRequirement> routes = new LinkedHashMap<>();
        for (DothanProxyRequirement requirement : requirements) {
            routes.put(requirement.listenPort, new DothanProxyRequirement(
                    requirement.serverHost, requirement.serverPort, requirement.listenPort));
        }
        this.requirementsByListenPort = Map.copyOf(routes);
        this.whitelist = Set.copyOf(whitelist);
        this.blacklist = Set.copyOf(blacklist);
        this.transferMode = transferMode;
        this.secureTransportMode = secureTransportMode;
        this.transferKey = transferKey;
        this.tlsKeyStorePath = tlsKeyStorePath;
        this.tlsKeyStorePassword = tlsKeyStorePassword;
        this.tlsTrustStorePath = tlsTrustStorePath;
        this.tlsTrustStorePassword = tlsTrustStorePassword;
        this.verbose = verbose;
    }

    public int getVersion() {
        return version;
    }

    public List<DothanProxyRequirement> getDothanProxyRequirements() {
        return List.copyOf(requirementsByListenPort.values());
    }

    public Set<Integer> getListenPorts() {
        return requirementsByListenPort.keySet();
    }

    public Optional<DothanProxyRequirement> findRequirement(int listenPort) {
        return Optional.ofNullable(requirementsByListenPort.get(listenPort));
    }

    public Set<String> getWhitelist() {
        return whitelist;
    }

    public Set<String> getBlacklist() {
        return blacklist;
    }

    public DothanTransferModeEnum getTransferMode() {
        return transferMode;
    }

    public SecureTransportModeEnum getSecureTransportMode() {
        return secureTransportMode;
    }

    public String getTransferKey() {
        return transferKey;
    }

    public String getTlsKeyStorePath() {
        return tlsKeyStorePath;
    }

    public String getTlsKeyStorePassword() {
        return tlsKeyStorePassword;
    }

    public String getTlsTrustStorePath() {
        return tlsTrustStorePath;
    }

    public String getTlsTrustStorePassword() {
        return tlsTrustStorePassword;
    }

    public boolean isVerbose() {
        return verbose;
    }
}
