package io.memsentry.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable configuration parsed from the {@code -javaagent:memsentry.jar=...} argument
 * string: a comma-separated list of {@code key=value} pairs, e.g.:
 *
 * <pre>-javaagent:memsentry.jar=interval=5s,histogram=15s,port=7077,webhook=https://...</pre>
 *
 * <p>Parsing is intentionally lenient — unknown keys are ignored and malformed values fall
 * back to defaults — so a bad argument can never stop the target application from starting.
 */
public final class AgentConfig {

    private final Duration heapInterval;
    private final Duration histogramInterval;
    private final int httpPort;
    private final String dumpDir;
    private final boolean autoDump;
    private final Duration dumpCooldown;
    private final String webhookUrl;
    private final int window;
    private final double minGrowthBytesPerSec;
    private final double minRSquared;
    private final long minBytesFloor;
    private final boolean jfrEnabled;
    private final boolean verbose;

    private AgentConfig(Builder b) {
        this.heapInterval = b.heapInterval;
        this.histogramInterval = b.histogramInterval;
        this.httpPort = b.httpPort;
        this.dumpDir = b.dumpDir;
        this.autoDump = b.autoDump;
        this.dumpCooldown = b.dumpCooldown;
        this.webhookUrl = b.webhookUrl;
        this.window = b.window;
        this.minGrowthBytesPerSec = b.minGrowthBytesPerSec;
        this.minRSquared = b.minRSquared;
        this.minBytesFloor = b.minBytesFloor;
        this.jfrEnabled = b.jfrEnabled;
        this.verbose = b.verbose;
    }

    public Duration heapInterval() { return heapInterval; }
    public Duration histogramInterval() { return histogramInterval; }
    public int httpPort() { return httpPort; }
    public String dumpDir() { return dumpDir; }
    public boolean autoDump() { return autoDump; }
    public Duration dumpCooldown() { return dumpCooldown; }
    public String webhookUrl() { return webhookUrl; }
    public boolean hasWebhook() { return webhookUrl != null && !webhookUrl.isBlank(); }
    public int window() { return window; }
    public double minGrowthBytesPerSec() { return minGrowthBytesPerSec; }
    public double minRSquared() { return minRSquared; }
    public long minBytesFloor() { return minBytesFloor; }
    public boolean jfrEnabled() { return jfrEnabled; }
    public boolean verbose() { return verbose; }

    public static AgentConfig parse(String agentArgs) {
        Map<String, String> kv = new HashMap<>();
        if (agentArgs != null && !agentArgs.isBlank()) {
            for (String pair : agentArgs.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq).trim().toLowerCase();
                    String value = pair.substring(eq + 1).trim();
                    if (!key.isEmpty()) {
                        kv.put(key, value);
                    }
                }
            }
        }

        Builder b = new Builder();
        b.heapInterval = parseDuration(kv.get("interval"), Duration.ofSeconds(10));
        b.histogramInterval = parseDuration(kv.get("histogram"), Duration.ofSeconds(30));
        b.httpPort = parseInt(kv.get("port"), 7077);
        b.dumpDir = kv.getOrDefault("dumpdir", "heapdumps");
        b.autoDump = parseBool(kv.get("autodump"), true);
        b.dumpCooldown = parseDuration(kv.get("dumpcooldown"), Duration.ofMinutes(10));
        b.webhookUrl = kv.getOrDefault("webhook", "");
        b.window = Math.max(4, parseInt(kv.get("window"), 12));
        b.minGrowthBytesPerSec = mbPerMinToBytesPerSec(parseDouble(kv.get("mingrowth"), 1.0));
        b.minRSquared = parseDouble(kv.get("minr2"), 0.85);
        b.minBytesFloor = (long) (parseDouble(kv.get("minbytes"), 5.0) * 1024 * 1024);
        b.jfrEnabled = parseBool(kv.get("jfr"), true);
        b.verbose = parseBool(kv.get("verbose"), false);
        return new AgentConfig(b);
    }

    private static final class Builder {
        Duration heapInterval;
        Duration histogramInterval;
        int httpPort;
        String dumpDir;
        boolean autoDump;
        Duration dumpCooldown;
        String webhookUrl;
        int window;
        double minGrowthBytesPerSec;
        double minRSquared;
        long minBytesFloor;
        boolean jfrEnabled;
        boolean verbose;
    }

    private static double mbPerMinToBytesPerSec(double mbPerMin) {
        return mbPerMin * 1024.0 * 1024.0 / 60.0;
    }

    static Duration parseDuration(String value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            String v = value.trim().toLowerCase();
            if (v.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2).trim()));
            }
            if (v.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1).trim()));
            }
            if (v.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1).trim()));
            }
            return Duration.ofSeconds(Long.parseLong(v));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static boolean parseBool(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) {
            return true;
        }
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) {
            return false;
        }
        return fallback;
    }

    @Override
    public String toString() {
        return "AgentConfig{heap=" + heapInterval + ", histogram=" + histogramInterval
                + ", port=" + httpPort + ", autoDump=" + autoDump + ", jfr=" + jfrEnabled
                + ", window=" + window + ", webhook=" + (hasWebhook() ? "set" : "none") + "}";
    }
}
