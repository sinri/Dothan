package io.github.sinri.Dothan.Config;

import io.github.sinri.Dothan.DothanProxy.DothanProxyRequirement;
import io.github.sinri.Dothan.DothanProxy.SecureTransportModeEnum;
import io.github.sinri.Dothan.DothanProxy.DothanTransferModeEnum;
import io.vertx.core.internal.logging.LoggerFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.validator.routines.InetAddressValidator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;

public class DothanConfig {
    private static DothanConfig instance = null;
    private String configFilePath;
    private ArrayList<DothanProxyRequirement> dothanProxyRequirements;
    private int version;
    private HashSet<String> whitelist;
    private HashSet<String> blacklist;
    private DothanTransferModeEnum transferMode;
    private SecureTransportModeEnum secureTransportMode;
    private String transferKey;
    private String tlsKeyStorePath;
    private String tlsKeyStorePassword;
    private String tlsTrustStorePath;
    private String tlsTrustStorePassword;
    private boolean verbose;// detail log mode

    private DothanConfig() {
        resetConfiguration();
    }

    public static DothanConfig getInstance() {
        if (instance == null) instance = new DothanConfig();
        return instance;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    public void setConfigFilePath(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    public ArrayList<DothanProxyRequirement> getDothanProxyRequirements() {
        return dothanProxyRequirements;
    }

    public int getVersion() {
        return version;
    }

    public HashSet<String> getWhitelist() {
        return whitelist;
    }

    public HashSet<String> getBlacklist() {
        return blacklist;
    }

    public DothanTransferModeEnum getTransferMode() {
        return transferMode;
    }

    public String getTransferKey() {
        return transferKey;
    }

    public SecureTransportModeEnum getSecureTransportMode() {
        return secureTransportMode;
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

    public void loadFromConfigFile() throws IOException {
        var logger=LoggerFactory.getLogger(this.getClass());
        resetConfiguration();
        try (var lines = Files.lines((new File(this.configFilePath)).toPath())) {
            lines.forEach(s -> {
                s = s.trim();

                if (s.matches("^# Dothan Config Version \\d+$")) {
                    String str_version = s.trim().substring(24);
                    version = Integer.parseInt(str_version);
                    logger.info("READ Version: " + version);
                } else if (s.matches("^\\+ .+$")) {
                    String ip = s.trim().substring(2);
                    if (InetAddressValidator.getInstance().isValid(ip)) {
                        whitelist.add(ip);
                        logger.info("READ WHITELIST: " + ip);
                    }
                } else if (s.matches("^- .+$")) {
                    String ip = s.trim().substring(2);
                    if (InetAddressValidator.getInstance().isValid(ip)) {
                        blacklist.add(ip);
                        logger.info("READ BLACKLIST: " + ip);
                    }
                } else if (s.matches("^# MODE [A-Z]+$")) {
                    String str_mode = s.trim().substring(7);
                    transferMode = DothanTransferModeEnum.valueOf(str_mode);
                    logger.info("READ TRANSFER MODE: " + transferMode);
                } else if (s.matches("^# TRANSFER KEY .+$")) {
                    transferKey = s.trim().substring(15);
                    logger.info("READ TRANSFER KEY: [REDACTED]");
                } else if (s.matches("^# SECURE TRANSPORT (RECORD|TLS)$")) {
                    secureTransportMode = SecureTransportModeEnum.valueOf(s.trim().substring(19));
                    logger.info("READ SECURE TRANSPORT: " + secureTransportMode);
                } else if (s.matches("^# TLS KEYSTORE PATH .+$")) {
                    tlsKeyStorePath = s.trim().substring(20);
                    logger.info("READ TLS KEYSTORE PATH: " + tlsKeyStorePath);
                } else if (s.matches("^# TLS KEYSTORE PASSWORD .+$")) {
                    tlsKeyStorePassword = s.trim().substring(24);
                    logger.info("READ TLS KEYSTORE PASSWORD: [REDACTED]");
                } else if (s.matches("^# TLS TRUSTSTORE PATH .+$")) {
                    tlsTrustStorePath = s.trim().substring(22);
                    logger.info("READ TLS TRUSTSTORE PATH: " + tlsTrustStorePath);
                } else if (s.matches("^# TLS TRUSTSTORE PASSWORD .+$")) {
                    tlsTrustStorePassword = s.trim().substring(26);
                    logger.info("READ TLS TRUSTSTORE PASSWORD: [REDACTED]");
                } else if (s.matches("^\\d+:.+:\\d+$")) {
                    // since 5.x
                    String[] parts = s.split(":");
                    dothanProxyRequirements.add(new DothanProxyRequirement(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[0])));
                    logger.info("READ PROXY REQUIREMENT: " + parts[0] + " -> " + parts[1] + ":" + parts[2]);
                } else if (s.matches("^\\d+\\s+[^:\\s]+:\\d+$")) {
                    // from 3.x to 4.x
                    String[] parts = s.split("(\\s+)|:");
                    String vl = parts[0];
                    String vh = parts[1];
                    String vp = parts[2];

                    dothanProxyRequirements.add(new DothanProxyRequirement(vh, Integer.parseInt(vp), Integer.parseInt(vl)));
                    logger.info("READ PROXY REQUIREMENT: " + parts[0] + " -> " + parts[1] + ":" + parts[2]);
                } else {
                    if (!s.isEmpty()) {
                        logger.warn("Cannot parse this line, ignore it: \n" + s);
                    }
                }
            });
        }
        validateSecureTransportConfiguration();
    }

    public void loadFromCommandLineOptions(CommandLine options) {
        resetConfiguration();

        String vh = "127.0.0.1";
        String vp = "3306";
        String vl = "20001";
        vh = options.getOptionValue("h", vh);
        vp = options.getOptionValue("p", vp);
        vl = options.getOptionValue("l", vl);

        DothanProxyRequirement dv = new DothanProxyRequirement(vh, Integer.parseInt(vp), Integer.parseInt(vl));
        dothanProxyRequirements.add(dv);

        if (options.hasOption("w")) {
            for (String ip : options.getOptionValue("w", "").split(",")) {
                if (InetAddressValidator.getInstance().isValid(ip)) {
                    whitelist.add(ip);
                }
            }
        }
        if (options.hasOption("b")) {
            for (String ip : options.getOptionValue("b", "").split(",")) {
                if (InetAddressValidator.getInstance().isValid(ip)) {
                    blacklist.add(ip);
                }
            }
        }

        version = -1;
    }

    private void resetConfiguration() {
        dothanProxyRequirements = new ArrayList<>();
        whitelist = new HashSet<>();
        blacklist = new HashSet<>();
        transferMode = DothanTransferModeEnum.PLAIN;
        secureTransportMode = SecureTransportModeEnum.RECORD;
        transferKey = "";
        tlsKeyStorePath = "";
        tlsKeyStorePassword = "";
        tlsTrustStorePath = "";
        tlsTrustStorePassword = "";
        version = 0;
    }

    private void validateSecureTransportConfiguration() throws IOException {
        if (transferMode == DothanTransferModeEnum.PLAIN) {
            return;
        }
        if (secureTransportMode == SecureTransportModeEnum.RECORD) {
            if (transferKey.isBlank()) {
                throw new IOException("TRANSFER KEY is required for RECORD secure transport");
            }
            return;
        }
        if (tlsKeyStorePath.isBlank() || tlsKeyStorePassword.isBlank()
                || tlsTrustStorePath.isBlank() || tlsTrustStorePassword.isBlank()) {
            throw new IOException("TLS secure transport requires keystore and truststore paths and passwords");
        }
    }
}
