package io.github.sinri.Dothan;

import io.github.sinri.Dothan.Config.DothanConfigException;
import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigParser;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.github.sinri.Dothan.DothanProxy.DothanRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;

public class Dothan {
    /**
     * The entry point of the Dothan application.
     *
     * <p>This method processes command-line arguments to configure and start the Dothan proxy server.
     * Users can provide options via command-line to set configuration file, database host/port,
     * listening port, IP whitelist/blacklist, verbosity, and other settings.
     *
     * @param args the command line arguments passed to the application
     */
    public static void main(String[] args) {
        Logger logger = LoggerFactory.getLogger(Dothan.class);
        System.out.println("Dothan 7.1");

        Options optionsDefinition = options();
        try {
            CommandLine options = new DefaultParser().parse(optionsDefinition, args);
            if (options.hasOption("help")) {
                help(optionsDefinition);
                return;
            }

            boolean verbose = options.hasOption("v") || options.hasOption("d");
            boolean hotReloadDisabled = options.hasOption("k");
            Path configPath = null;
            DothanConfigSnapshot initial;
            if (options.hasOption("c")) {
                configPath = Path.of(options.getOptionValue("c", "dothan.config")).toAbsolutePath();
                initial = DothanConfigParser.parse(configPath, verbose);
            } else if (options.hasOption("h") && options.hasOption("p") && options.hasOption("l")) {
                initial = DothanConfigParser.parse(options, verbose);
                hotReloadDisabled = true;
            } else {
                help(optionsDefinition);
                return;
            }

            Vertx vertx = Vertx.vertx();
            DothanConfigManager configManager = new DothanConfigManager();
            DothanRuntime runtime = new DothanRuntime(vertx, configManager);
            try {
                runtime.start(initial).await();
                logger.info("Dothan deployment completed with config version " + initial.getVersion());
                installShutdownHook(runtime, vertx, logger);

                if (!hotReloadDisabled) {
                    watch(configPath, verbose, runtime, logger);
                }
            } catch (Exception error) {
                closeAfterFailure(runtime, vertx, error);
                throw error;
            }
        } catch (ParseException error) {
            logger.error("Failed to parse arguments %s: %s".formatted(Arrays.asList(args), error.getMessage()), error);
        } catch (DothanConfigException | IOException error) {
            logger.error("Invalid configuration: " + error.getMessage(), error);
        } catch (Exception error) {
            logger.error("Dothan failed: " + error.getMessage(), error);
        }
    }

    private static void watch(Path configPath, boolean verbose, DothanRuntime runtime, Logger logger)
            throws IOException, InterruptedException {
        Path directory = configPath.getParent();
        String fileName = configPath.getFileName().toString();
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            while (!Thread.currentThread().isInterrupted()) {
                if (!checkFileChange(watchService, fileName)) {
                    continue;
                }
                try {
                    DothanConfigSnapshot candidate = DothanConfigParser.parse(configPath, verbose);
                    runtime.reload(candidate).await();
                } catch (DothanConfigException | IOException error) {
                    logger.error("Rejected configuration candidate; active configuration is unchanged: "
                            + error.getMessage(), error);
                } catch (Exception error) {
                    logger.error("Configuration reload failed; active configuration is unchanged: "
                            + error.getMessage(), error);
                }
            }
        }
    }

    private static boolean checkFileChange(WatchService watchService, String fileName)
            throws InterruptedException {
        WatchKey key = watchService.take();
        boolean changed = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                changed = true;
                continue;
            }
            Path changedPath = (Path) event.context();
            if (changedPath.toFile().getName().equals(fileName)) {
                changed = true;
            }
        }
        if (!key.reset()) {
            throw new IllegalStateException("configuration directory watch key is no longer valid");
        }
        return changed;
    }

    private static void installShutdownHook(DothanRuntime runtime, Vertx vertx, Logger logger) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                runtime.close().await();
            } catch (Exception error) {
                logger.error("Failed to close Dothan listeners: " + error.getMessage(), error);
            }
            try {
                vertx.close().await();
            } catch (Exception error) {
                logger.error("Failed to close Vert.x: " + error.getMessage(), error);
            }
        }, "dothan-shutdown"));
    }

    private static void closeAfterFailure(DothanRuntime runtime, Vertx vertx, Exception primaryError) {
        try {
            runtime.close().await();
        } catch (Exception cleanupError) {
            primaryError.addSuppressed(cleanupError);
        }
        try {
            vertx.close().await();
        } catch (Exception cleanupError) {
            primaryError.addSuppressed(cleanupError);
        }
    }

    private static Options options() {
        Options options = new Options();
        options.addOption("help", "Display help information");
        options.addOption("c", true, "Set proxy config file. If not use this, h,p and l are needed.");
        options.addOption("h", true, "database host");
        options.addOption("p", true, "database port");
        options.addOption("l", true, "listen local port");
        options.addOption("w", true, "whitelist, separate IP with comma (as of 4.0)");
        options.addOption("b", true, "blacklist, separate IP with comma (as of 4.0)");
        options.addOption("d", "use detail mode");
        options.addOption("k", "keep config and no hot update");
        options.addOption("v", "verbose");
        return options;
    }

    private static void help(Options options) {
        new HelpFormatter().printHelp("options", options);
    }
}
