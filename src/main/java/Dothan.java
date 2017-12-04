import DothanProxy.DothanConfigItem;
import DothanProxy.DothanConfigParser;
import DothanProxy.DothanHelper;
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
        System.out.println("Dothan 3.0");

        Options ops = new Options();
        ops.addOption("help", "Display help information");
        ops.addOption("c", true, "Set proxy config file. If not use this, h,p and l are needed.");
        //ops.addOption("r", "Run");
        ops.addOption("h", true, "database host");
        ops.addOption("p", true, "database port");
        ops.addOption("l", true, "listen local port");
        ops.addOption("d", "use detail mode");
        ops.addOption("k", "keep config and no hot update");

        try {
            CommandLine options = new DefaultParser().parse(ops, args);

            ArrayList<DothanConfigItem> dothanVerticleConfigs = new ArrayList<>();

            if (options.hasOption("help")) {
                help(ops);
                return;
            }

            int configVersion;
            DothanConfigParser dothanConfigParser = null;
            String configFilePath = "";

            boolean hotConfigOff = options.hasOption("k");

            if (options.hasOption("c")) {
                configFilePath = options.getOptionValue("c", "dothan.config");
                dothanConfigParser = new DothanConfigParser(configFilePath);
                ArrayList<DothanConfigItem> configItems = dothanConfigParser.getConfigItems();
                configVersion = dothanConfigParser.getVersion();
                LoggerFactory.getLogger(Dothan.class).info("dothanConfigParser read version: " + configVersion);
                dothanVerticleConfigs.addAll(configItems);
            } else {
                if (options.hasOption("h") && options.hasOption("p") && options.hasOption("l")) {
                    String vh = "127.0.0.1";
                    String vp = "3306";
                    String vl = "20001";
                    vh = options.getOptionValue("h", vh);
                    vp = options.getOptionValue("p", vp);
                    vl = options.getOptionValue("l", vl);

                    DothanConfigItem dv = new DothanConfigItem(vh, Integer.parseInt(vp), Integer.parseInt(vl));
                    dothanVerticleConfigs.add(dv);

                    configVersion = -1;
                } else {
                    //throw new Exception("If [c]onfig not given, [h]ost, [p]ort and [l]isten port are required!");
                    //configFilePath = "dothan.config";
                    help(ops);
                    return;
                }
            }

            DothanHelper.setDetailMode(options.hasOption("d"));

            Dothan.deployAll(dothanVerticleConfigs);

            LoggerFactory.getLogger(Dothan.class).debug("dothanConfigParser:" + dothanConfigParser);
            LoggerFactory.getLogger(Dothan.class).debug("configFilePath:" + configFilePath);

            if (dothanConfigParser == null || configFilePath.equals("") || hotConfigOff) {
                return;
            }

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
            while (true) {
                boolean configFileChanged = checkFileChange(watchService, pure_file_name);
                if (!configFileChanged) continue;
                final String[] stateMachine = {DothanHotUpdateVersionStateOfWatching};
                int checkingVersion;
                while (true) {
                    switch (stateMachine[0]) {
                        case DothanHotUpdateVersionStateOfWatching:
                            checkingVersion = dothanConfigParser.getVersion();
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
                            ArrayList<DothanConfigItem> configItems = dothanConfigParser.getConfigItems();
                            dothanVerticleConfigs.clear();
                            dothanVerticleConfigs.addAll(configItems);

                            Dothan.deployAll(dothanVerticleConfigs);
                            configVersion = dothanConfigParser.getVersion();
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

        } catch (ParseException e) {
            LoggerFactory.getLogger(Dothan.class).error("解析参数失败，参数：[" + Arrays.asList(args).toString() + "] ! " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            LoggerFactory.getLogger(Dothan.class).error(e.getMessage());
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

    private static void deployAll(ArrayList<DothanConfigItem> dothanVerticleConfigs) {
        dothanVerticleConfigs.forEach(dothanVerticleConfig -> {
            DothanVerticle dothanVerticle = new DothanVerticle(dothanVerticleConfig);
            LoggerFactory.getLogger(Dothan.class).info("Ready to listen on port " + dothanVerticleConfig.listenPort + " " +
                    "for database " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
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
