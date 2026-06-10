package io.memsentry.agent.util;

/**
 * Minimal, dependency-free logger for the agent.
 *
 * <p>An agent shares the target application's process, so we avoid pulling in a
 * logging framework that could clash with the host's classpath. Everything goes
 * to stdout behind a recognizable prefix.
 */
public final class Log {

    private static final String PREFIX = "[MemSentry]";
    private static volatile boolean verbose = false;

    private Log() {
    }

    public static void setVerbose(boolean v) {
        verbose = v;
    }

    public static void info(String msg) {
        System.out.println(PREFIX + " " + msg);
    }

    public static void warn(String msg) {
        System.out.println(PREFIX + "[WARN] " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.out.println(PREFIX + "[ERROR] " + msg);
        if (t != null) {
            t.printStackTrace(System.out);
        }
    }

    public static void debug(String msg) {
        if (verbose) {
            System.out.println(PREFIX + "[DEBUG] " + msg);
        }
    }
}
