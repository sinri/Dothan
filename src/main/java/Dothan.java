import DothanProxy.DothanConfigItem;
import DothanProxy.DothanConfigParser;
import DothanProxy.DothanHelper;
import DothanProxy.DothanVerticle;
import io.vertx.core.Vertx;
import org.apache.commons.cli.*;

import java.util.ArrayList;
import java.util.Arrays;

public class Dothan {
    static Vertx instance;

    static Vertx getInstance() {
        if (instance == null) {
            instance = Vertx.vertx();
        }
        return instance;
    }

    public static void main_1_1(String[] args) {
        System.out.println("Dothan 1.1");
        if (args.length != 3) {
            System.out.println("Usage: java -jar Dothan.jar SERVER_HOST SERVER_PORT LISTEN_PORT");
            return;
        }
        String host = args[0];
        String port = args[1];
        String listenPort = args[2];
        System.out.println("Listen on port " + Integer.parseInt(listenPort) + ", proxy for server " + host + ":" + Integer.parseInt(port));
        getInstance().deployVerticle(new DothanVerticle(host, Integer.parseInt(port), Integer.parseInt(listenPort)));
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

        try {
            CommandLine options = new DefaultParser().parse(ops, args);

            ArrayList<DothanConfigItem> dothanVerticleConfigs = new ArrayList<>();

            if (options.hasOption("help")) {
                //System.out.println("Usage: java -jar Dothan.jar SERVER_HOST SERVER_PORT LISTEN_PORT");
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("options", ops);
                return;
            }

            int configVersion;
            DothanConfigParser dothanConfigParser = null;

            if (options.hasOption("c")) {
                String configFilePath = options.getOptionValue("c", "dothan.config");
                dothanConfigParser = new DothanConfigParser(configFilePath);
                ArrayList<DothanConfigItem> configItems = dothanConfigParser.getConfigItems();
                configVersion = dothanConfigParser.getVersion();
                DothanHelper.logger.info("dothanConfigParser read version: " + configVersion);
                configItems.forEach(dothanVerticleConfigs::add);
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
                    throw new Exception("If [c]onfig not given, [h]ost, [p]ort and [l]isten port are required!");
                }
            }

            DothanHelper.setDetailMode(options.hasOption("d"));

            Dothan.deployAll(dothanVerticleConfigs);

            if (dothanConfigParser != null) {

                final String[] stateMachine = {DothanHotUpdateVersionStateOfWatching};

                while (true) {
                    int checkingVersion;
                    switch (stateMachine[0]) {
                        case DothanHotUpdateVersionStateOfWatching:
                            checkingVersion = dothanConfigParser.getVersion();
                            DothanHelper.logger.info("Check version [" + checkingVersion + "] against loaded version " + configVersion);
                            if (checkingVersion > configVersion) {
                                // a new version came
                                DothanHelper.logger.info("Ready to reload version " + checkingVersion);
                                stateMachine[0] = DothanHotUpdateVersionStateOfClosing;
                                getInstance().close(voidAsyncResult -> {
                                    if (voidAsyncResult.succeeded()) {
                                        DothanHelper.logger.info("instance closed");
                                        stateMachine[0] = DothanHotUpdateVersionStateOfClosed;
                                        instance = null;
                                    } else {
                                        DothanHelper.logger.error("instance failed to close! " + voidAsyncResult.cause().getMessage());
                                        stateMachine[0] = DothanHotUpdateVersionStateOfCloseFailed;
                                    }
                                });
                            }
                            break;
                        case DothanHotUpdateVersionStateOfClosing:
                            // do nothing
                            DothanHelper.logger.info("waiting for instance closing");
                            break;
                        case DothanHotUpdateVersionStateOfCloseFailed:
                            // try again
                            DothanHelper.logger.info("failed to close let us try again");
                            stateMachine[0] = DothanHotUpdateVersionStateOfClosing;
                            getInstance().close(voidAsyncResult -> {
                                if (voidAsyncResult.succeeded()) {
                                    DothanHelper.logger.info("instance closed after failed");
                                    stateMachine[0] = DothanHotUpdateVersionStateOfClosed;
                                    instance = null;
                                    //deploymentIdList.clear();
                                } else {
                                    DothanHelper.logger.error("instance failed to close again! " + voidAsyncResult.cause().getMessage());
                                    stateMachine[0] = DothanHotUpdateVersionStateOfCloseFailed;
                                }
                            });
                            break;
                        case DothanHotUpdateVersionStateOfClosed:
                            //deploy
                            DothanHelper.logger.info("instance closed so deploy new config");
                            ArrayList<DothanConfigItem> configItems = dothanConfigParser.getConfigItems();
                            dothanVerticleConfigs.clear();
                            configItems.forEach(dothanConfigItem -> {
                                dothanVerticleConfigs.add(dothanConfigItem);
                            });

                            Dothan.deployAll(dothanVerticleConfigs);
                            configVersion = dothanConfigParser.getVersion();
                            DothanHelper.logger.info("Reloaded version " + configVersion);
                            stateMachine[0] = DothanHotUpdateVersionStateOfWatching;
                            break;
                    }


                    switch (stateMachine[0]) {
                        case DothanHotUpdateVersionStateOfWatching:
                            Thread.sleep(1000 * 10);//check config file every ten seconds
                            break;
                        case DothanHotUpdateVersionStateOfClosing:
                        case DothanHotUpdateVersionStateOfCloseFailed:
                            Thread.sleep(1000);
                            break;
                        default:
                            // no sleep
                            break;
                    }
                }
            }
        } catch (ParseException e) {
            DothanHelper.logger.error("解析参数失败，参数：[" + Arrays.asList(args).toString() + "] ! " + e.getMessage());
        } catch (Exception e) {
            DothanHelper.logger.error(e.getMessage());
        }
    }

    final static String DothanHotUpdateVersionStateOfWatching = "watching";
    final static String DothanHotUpdateVersionStateOfClosing = "closing";
    final static String DothanHotUpdateVersionStateOfClosed = "closed";
    final static String DothanHotUpdateVersionStateOfCloseFailed = "close_failed";

    private static ArrayList<String> deployAll(ArrayList<DothanConfigItem> dothanVerticleConfigs) {
        ArrayList<String> deploymentIdList = new ArrayList<>();

        dothanVerticleConfigs.forEach(dothanVerticleConfig -> {
            DothanVerticle dothanVerticle = new DothanVerticle(dothanVerticleConfig);
            DothanHelper.logger.info("Ready to listen on port " + dothanVerticleConfig.listenPort + " " +
                    "for database " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
            getInstance().deployVerticle(dothanVerticle, res -> {
                if (res.succeeded()) {
                    DothanHelper.logger.info("Deployment id is: " + res.result() + " ! " + dothanVerticleConfig.listenPort + " for " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
                    deploymentIdList.add(res.result());
                } else {
                    DothanHelper.logger.error("Deployment failed! " + res.cause() + " " + dothanVerticleConfig.listenPort + " for " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
                }
            });
        });

        return deploymentIdList;
    }
}
