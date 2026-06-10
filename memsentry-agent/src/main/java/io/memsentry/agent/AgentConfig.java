package io.memsentry.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable configuration parsed from the {@code -javaagent:memsentry.jar=...} argument
 * string. The argument is a comma-separated list of {@code key=value} pairs, e.g.:
 *
 * <pre>-javaagent:memsentry.jar=interval=5s,verbose=true</pre>
 *
 * <p>Parsing is intentionally lenient: unknown keys are ignored and malformed values
 * fall back to defaults, so a bad argument can never prevent the target app from starting.
 */
public final class AgentConfig {

    private final Duration sampleInterval;
    private final boolean verbose;

    private AgentConfig(Duration sampleInterval, boolean verbose) {
        this.sampleInterval = sampleInterval;
        this.verbose = verbose;
    }

    public Duration sampleInterval() {
        return sampleInterval;
    }

    public boolean verbose() {
        return verbose;
    }

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

        Duration interval = parseDuration(kv.getOrDefault("interval", "10s"), Duration.ofSeconds(10));
        boolean verbose = Boolean.parseBoolean(kv.getOrDefault("verbose", "false"));
        return new AgentConfig(interval, verbose);
    }

    /**
     * Parses a duration written as {@code <number><unit>} where unit is one of
     * {@code ms}, {@code s}, {@code m}. A bare number is treated as seconds.
     * Returns {@code fallback} on any parse failure.
     */
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

    @Override
    public String toString() {
        return "AgentConfig{interval=" + sampleInterval + ", verbose=" + verbose + "}";
    }
}
