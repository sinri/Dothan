package Logging;


import io.vertx.core.internal.logging.LoggerFactory;

public class DefaultLoggerTest {
    public static void main(String[] args) {
        allKindsOfLogging("BEFORE");
    }

    private static void allKindsOfLogging(String note) {
        LoggerFactory.getLogger(DefaultLoggerTest.class).trace("Some trace " + note);
        LoggerFactory.getLogger(DefaultLoggerTest.class).debug("Some debug " + note);
        LoggerFactory.getLogger(DefaultLoggerTest.class).info("Some info " + note);
        LoggerFactory.getLogger(DefaultLoggerTest.class).warn("Some warn " + note);
        LoggerFactory.getLogger(DefaultLoggerTest.class).error("Some error " + note);
//        LoggerFactory.getLogger(DefaultLoggerTest.class).fatal("Some fatal " + note);
    }
}
