package io.memsentry.agent.sampling;

import io.memsentry.agent.util.Log;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Samples the live-object class histogram from inside the process — the programmatic
 * equivalent of {@code jmap -histo:live} — via the platform {@code DiagnosticCommand}
 * MBean's {@code gcClassHistogram} operation.
 *
 * <p><b>Trade-off (by design):</b> the live histogram forces a full GC, so this is the
 * agent's most expensive operation. We run it on a deliberately slow cadence (default
 * 30s, configurable) and treat it as the source of truth for <i>retained</i> growth:
 * bytes that survive a full GC and keep climbing are the real leak signal, not transient
 * allocation churn.
 */
public final class ClassHistogramSampler {

    private static final String DIAGNOSTIC_COMMAND = "com.sun.management:type=DiagnosticCommand";
    private static final String OPERATION = "gcClassHistogram";

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private final ObjectName objectName;
    private volatile boolean available = true;

    public ClassHistogramSampler() {
        ObjectName name = null;
        try {
            name = new ObjectName(DIAGNOSTIC_COMMAND);
        } catch (Exception e) {
            available = false;
            Log.warn("DiagnosticCommand MBean unavailable; class histogram disabled");
        }
        this.objectName = name;
    }

    public boolean isAvailable() {
        return available && objectName != null;
    }

    /**
     * Returns the current live-object histogram, or an empty list if a sample could not
     * be taken (never throws — a failed sample must not disturb the host application).
     */
    public List<ClassCount> sample() {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            // No arguments => live objects only (a full GC precedes the count).
            Object result = server.invoke(
                    objectName,
                    OPERATION,
                    new Object[]{new String[0]},
                    new String[]{String[].class.getName()});
            return parse(String.valueOf(result));
        } catch (Exception e) {
            available = false;
            Log.error("class histogram sample failed; disabling histogram sampling", e);
            return List.of();
        }
    }

    /**
     * Parses {@code GC.class_histogram} output. Expected row shape:
     *
     * <pre> num     #instances         #bytes  class name (module)
     *    1:        123456       98765432  [B (java.base@21)</pre>
     */
    static List<ClassCount> parse(String text) {
        List<ClassCount> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            // Data rows start with "<rank>:". Skip headers, separators, and the Total row.
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || !isAllDigits(trimmed, colon)) {
                continue;
            }
            String[] tok = trimmed.split("\\s+");
            // tok[0]="<rank>:", tok[1]=instances, tok[2]=bytes, tok[3]=className, [tok[4]=module]
            if (tok.length < 4) {
                continue;
            }
            try {
                long instances = Long.parseLong(tok[1]);
                long bytes = Long.parseLong(tok[2]);
                String className = tok[3];
                out.add(new ClassCount(className, instances, bytes));
            } catch (NumberFormatException ignored) {
                // Not a data row (e.g. the "Total" line whose columns differ) — skip.
            }
        }
        return out;
    }

    private static boolean isAllDigits(String s, int end) {
        for (int i = 0; i < end; i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return end > 0;
    }
}
