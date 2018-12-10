import Config.DothanConfig;
import DothanProxy.DothanProxyRequirement;
import DothanProxy.DothanVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.cli.*;

import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;

public class Dothan {
    private static Vertx instance;

    private static Vertx getInstance() {
        if (instance == null) {
            instance = Vertx.vertx();
        }
        return instance;
    }

    public static void main(String[] args) {
        System.out.println("Dothan 5.1");

        Options ops = new Options();
        ops.addOption("help", "Display help information");
        ops.addOption("c", true, "Set proxy config file. If not use this, h,p and l are needed.");
        //ops.addOption("r", "Run");
        ops.addOption("h", true, "database host");
        ops.addOption("p", true, "database port");
        ops.addOption("l", true, "listen local port");
        ops.addOption("w", true, "whitelist, separate IP with comma (as of 4.0)");
        ops.addOption("b", true, "blacklist, separate IP with comma (as of 4.0)");
        ops.addOption("d", "use detail mode");
        ops.addOption("k", "keep config and no hot update");

        try {
            CommandLine options = new DefaultParser().parse(ops, args);

            //ArrayList<DothanProxyRequirement> dothanVerticleConfigs = new ArrayList<>();

            if (options.hasOption("help")) {
                help(ops);
                return;
            }

            int configVersion = -1;
            //DothanConfigParser dothanConfigParser = null;
            String configFilePath = "";

            boolean hotConfigOff = options.hasOption("k");

            DothanConfig dothanConfig = DothanConfig.getInstance();

            if (options.hasOption("c")) {
                configFilePath = options.getOptionValue("c", "dothan.config");
                dothanConfig.setConfigFilePath(configFilePath);
                dothanConfig.loadFromConfigFile();
                configVersion = dothanConfig.getVersion();
            } else {
                if (options.hasOption("h") && options.hasOption("p") && options.hasOption("l")) {
                    dothanConfig.loadFromCommandLineOptions(options);
                    hotConfigOff = true; // use command line arguments as option so always keep mode
                } else {
                    help(ops);
                    return;
                }
            }

            //DothanHelper.setDetailMode(options.hasOption("d"));
            dothanConfig.setVerbose(options.hasOption("d"));

            Dothan.deployAll(dothanConfig.getDothanProxyRequirements());

            LoggerFactory.getLogger(Dothan.class).info("Dothan Main Deployment Done");

            if (!hotConfigOff) {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                String dir_to_monitor = (new File(configFilePath)).getAbsoluteFile().getParent();
                String pure_file_name = (new File(configFilePath)).getName();
                LoggerFactory.getLogger(Dothan.class).debug("dir_to_monitor:" + dir_to_monitor + " and file: " + pure_file_name);
                final Path path = Paths.get(dir_to_monitor);
                path.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE
                );

                //noinspection InfiniteLoopStatement
                while (true) {
                    boolean configFileChanged = checkFileChange(watchService, pure_file_name);
                    if (!configFileChanged) continue;
                    final String[] stateMachine = {DothanHotUpdateVersionStateOfWatching};
                    int checkingVersion;
                    while (true) {
                        switch (stateMachine[0]) {
                            case DothanHotUpdateVersionStateOfWatching:
                                dothanConfig.loadFromConfigFile();
                                checkingVersion = dothanConfig.getVersion();
                                LoggerFactory.getLogger(Dothan.class).debug("Check version [" + checkingVersion + "] against loaded version " + configVersion);
                                if (checkingVersion > configVersion) {
                                    // a new version came
                                    LoggerFactory.getLogger(Dothan.class).info("Ready to reload version " + checkingVersion);
                                    stateMachine[0] = DothanHotUpdateVersionStateOfClosing;
                                    getInstance().close(voidAsyncResult -> {
                                        if (voidAsyncResult.succeeded()) {
                                            LoggerFactory.getLogger(Dothan.class).info("instance closed");
                                            stateMachine[0] = DothanHotUpdateVersionStateOfClosed;
                                            instance = null;
                                        } else {
                                            LoggerFactory.getLogger(Dothan.class).error("instance failed to close! " + voidAsyncResult.cause().getMessage());
                                            stateMachine[0] = DothanHotUpdateVersionStateOfCloseFailed;
                                        }
                                    });
                                }
                                break;
                            case DothanHotUpdateVersionStateOfClosing:
                                // do nothing
                                LoggerFactory.getLogger(Dothan.class).debug("waiting for instance closing");
                                break;
                            case DothanHotUpdateVersionStateOfCloseFailed:
                                // try again
                                LoggerFactory.getLogger(Dothan.class).info("failed to close let us try again");
                                stateMachine[0] = DothanHotUpdateVersionStateOfClosing;
                                getInstance().close(voidAsyncResult -> {
                                    if (voidAsyncResult.succeeded()) {
                                        LoggerFactory.getLogger(Dothan.class).info("instance closed after failed");
                                        stateMachine[0] = DothanHotUpdateVersionStateOfClosed;
                                        instance = null;
                                        //deploymentIdList.clear();
                                    } else {
                                        LoggerFactory.getLogger(Dothan.class).error("instance failed to close again! " + voidAsyncResult.cause().getMessage());
                                        stateMachine[0] = DothanHotUpdateVersionStateOfCloseFailed;
                                    }
                                });
                                break;
                            case DothanHotUpdateVersionStateOfClosed:
                                //deploy
                                LoggerFactory.getLogger(Dothan.class).info("instance closed so deploy new config");
                                Dothan.deployAll(dothanConfig.getDothanProxyRequirements());
                                configVersion = dothanConfig.getVersion();
                                LoggerFactory.getLogger(Dothan.class).info("Reloaded version " + configVersion);
                                stateMachine[0] = DothanHotUpdateVersionStateOfWatching;
                                break;
                        }

                        if (stateMachine[0].equals(DothanHotUpdateVersionStateOfClosing) || stateMachine[0].equals(DothanHotUpdateVersionStateOfCloseFailed)) {
                            Thread.sleep(1000);
                        } else if (stateMachine[0].equals(DothanHotUpdateVersionStateOfWatching)) {
                            break;
                        }
                    }
                }
            }
        } catch (ParseException e) {
            LoggerFactory.getLogger(Dothan.class).fatal("解析参数失败，参数：[" + Arrays.asList(args).toString() + "] ! " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            LoggerFactory.getLogger(Dothan.class).fatal(e.getMessage());
        }
    }

    private static boolean checkFileChange(WatchService watchService, String pure_file_name) throws InterruptedException {
        final WatchKey wk = watchService.take();
        boolean file_changed = false;
        for (WatchEvent<?> event : wk.pollEvents()) {
            final Path changedPath = (Path) event.context();
            if (changedPath.toFile().getName().equals(pure_file_name)) {
                LoggerFactory.getLogger(Dothan.class).info("CONFIG FILE " + pure_file_name + " CHANGED " + event.kind());
                file_changed = true;
            }
        }
        // reset the key
        boolean valid = wk.reset();
        if (!valid) {
            LoggerFactory.getLogger(Dothan.class).warn("Key has been unregistered");
        }
        return file_changed;
    }

    private final static String DothanHotUpdateVersionStateOfWatching = "watching";
    private final static String DothanHotUpdateVersionStateOfClosing = "closing";
    private final static String DothanHotUpdateVersionStateOfClosed = "closed";
    private final static String DothanHotUpdateVersionStateOfCloseFailed = "close_failed";

    private static void deployAll(ArrayList<DothanProxyRequirement> dothanVerticleConfigs) {
        dothanVerticleConfigs.forEach(dothanVerticleConfig -> {
            DothanVerticle dothanVerticle = new DothanVerticle(dothanVerticleConfig);
            LoggerFactory.getLogger(Dothan.class).info("Ready to listen on port " + dothanVerticleConfig.listenPort + " " +
                    "for SERVICE PROVIDER " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
            getInstance().deployVerticle(dothanVerticle, res -> {
                if (res.succeeded()) {
                    LoggerFactory.getLogger(Dothan.class).info("Deployment id is: " + res.result() + " ! " + dothanVerticleConfig.listenPort + " for " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
                } else {
                    LoggerFactory.getLogger(Dothan.class).error("Deployment failed! " + res.cause() + " " + dothanVerticleConfig.listenPort + " for " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
                }
            });
        });

    }

    private static void help(Options ops) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("options", ops);
    }
}
