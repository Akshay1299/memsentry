package io.memsentry.agent.alert;

import io.memsentry.agent.model.LeakSuspect;

import java.util.List;

/**
 * A leak notification payload handed to every {@link AlertSink}.
 *
 * @param title    short headline
 * @param leaks    the classes that crossed the leak threshold
 * @param dumpPath path to the captured heap dump, if any (may be null)
 */
public record Alert(String title, List<LeakSuspect> leaks, String dumpPath) {

    /** Human-readable multi-line summary shared by the log and webhook sinks. */
    public String renderText() {
        StringBuilder sb = new StringBuilder();
        sb.append(":rotating_light: ").append(title).append('\n');
        for (LeakSuspect s : leaks) {
            sb.append(String.format(
                    "• %s — %.1f MB now, growing %.2f MB/min (R²=%.2f)",
                    s.className(),
                    s.currentBytes() / (1024.0 * 1024.0),
                    s.growthMbPerMin(),
                    s.rSquared()));
            if (s.allocationSite() != null) {
                sb.append("  @ ").append(s.allocationSite());
            }
            sb.append('\n');
        }
        if (dumpPath != null) {
            sb.append("Heap dump: ").append(dumpPath).append('\n');
        }
        return sb.toString();
    }
}
