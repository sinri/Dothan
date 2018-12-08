package Config;

import DothanProxy.DothanProxyRequirement;
import DothanProxy.DothanTransferModeEnum;
import io.vertx.core.logging.LoggerFactory;
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
    private String transferKey;
    private boolean verbose;// detail log mode

    private DothanConfig() {
        dothanProxyRequirements = new ArrayList<>();
        whitelist = new HashSet<>();
        blacklist = new HashSet<>();
        transferMode = DothanTransferModeEnum.PLAIN;
        transferKey = "";
        version = 0;
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

    public void loadFromConfigFile() throws IOException {
        dothanProxyRequirements = new ArrayList<>();
        version = 0;
        Files.lines((new File(this.configFilePath)).toPath()).forEach(s -> {
            s = s.trim();

            if (s.matches("^# Dothan Config Version \\d+$")) {
                String str_version = s.trim().substring(24);
                version = Integer.parseInt(str_version);
                LoggerFactory.getLogger(this.getClass()).info("READ Version: " + version);
            } else if (s.matches("^\\+ .+$")) {
                String ip = s.trim().substring(2);
                if (InetAddressValidator.getInstance().isValid(ip)) {
                    whitelist.add(ip);
                    LoggerFactory.getLogger(this.getClass()).info("READ WHITELIST: " + ip);
                }
            } else if (s.matches("^- .+$")) {
                String ip = s.trim().substring(2);
                if (InetAddressValidator.getInstance().isValid(ip)) {
                    blacklist.add(ip);
                    LoggerFactory.getLogger(this.getClass()).info("READ BLACKLIST: " + ip);
                }
            } else if (s.matches("^# MODE [A-Z]+$")) {
                String str_mode = s.trim().substring(7);
                transferMode = DothanTransferModeEnum.valueOf(str_mode);
                LoggerFactory.getLogger(this.getClass()).info("READ TRANSFER MODE: " + transferMode);
            } else if (s.matches("^# TRANSFER KEY .+$")) {
                transferKey = s.trim().substring(15);
                LoggerFactory.getLogger(this.getClass()).info("READ TRANSFER KEY: " + transferKey);
            } else if (s.matches("^\\d+:.+:\\d+$")) {
                // since 5.x
                String[] parts = s.split(":");
                dothanProxyRequirements.add(new DothanProxyRequirement(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[0])));
                LoggerFactory.getLogger(this.getClass()).info("READ PROXY REQUIREMENT: " + parts[0] + " -> " + parts[1] + ":" + parts[2]);
            } else if (s.matches("^\\d+\\s+[^:\\s]+:\\d+$")) {
                // from 3.x to 4.x
                String[] parts = s.split("(\\s+)|:");
                String vl = parts[0];
                String vh = parts[1];
                String vp = parts[2];

                dothanProxyRequirements.add(new DothanProxyRequirement(vh, Integer.parseInt(vp), Integer.parseInt(vl)));
                LoggerFactory.getLogger(this.getClass()).info("READ PROXY REQUIREMENT: " + parts[0] + " -> " + parts[1] + ":" + parts[2]);
            } else {
                if (!s.isEmpty()) {
                    LoggerFactory.getLogger(this.getClass()).warn("Cannot parse this line, ignore it: \n" + s);
                }
            }
        });
    }

    public void loadFromCommandLineOptions(CommandLine options) {
        dothanProxyRequirements = new ArrayList<>();
        whitelist = new HashSet<>();
        blacklist = new HashSet<>();
        transferMode = DothanTransferModeEnum.PLAIN;
        transferKey = "";
        version = 0;

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
}
