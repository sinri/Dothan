import DothanProxy.DothanConfigItem;
import DothanProxy.DothanConfigParser;
import DothanProxy.DothanHelper;
import DothanProxy.DothanVerticle;
import io.vertx.core.Vertx;
import org.apache.commons.cli.*;

import java.util.ArrayList;
import java.util.Arrays;

public class Dothan {
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
        Vertx.vertx().deployVerticle(new DothanVerticle(host, Integer.parseInt(port), Integer.parseInt(listenPort)));
    }

    public static void main(String[] args) {
        System.out.println("Dothan 2.0");

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

            if (options.hasOption("c")) {
                String configFilePath = options.getOptionValue("c", "dothan.config");
                ArrayList<DothanConfigItem> configItems = new DothanConfigParser(configFilePath).getConfigItems();
                configItems.forEach(dothanConfigItem -> {
                    dothanVerticleConfigs.add(dothanConfigItem);

                });
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
                } else {
                    throw new Exception("If [c]onfig not given, [h]ost, [p]ort and [l]isten port are required!");
                }
            }

            DothanHelper.setDetailMode(options.hasOption("d"));

            dothanVerticleConfigs.forEach(dothanVerticleConfig -> {
                DothanVerticle dothanVerticle = new DothanVerticle(dothanVerticleConfig);
                DothanHelper.logger.info("Ready to listen on port " + dothanVerticleConfig.listenPort + " " +
                        "for database " + dothanVerticleConfig.serverHost + ":" + dothanVerticleConfig.serverPort);
                Vertx.vertx().deployVerticle(dothanVerticle);
            });

        } catch (ParseException e) {
            DothanHelper.logger.error("解析参数失败，参数：[" + Arrays.asList(args).toString() + "] ! " + e.getMessage());
        } catch (Exception e) {
            DothanHelper.logger.error(e.getMessage());
        }
    }
}
