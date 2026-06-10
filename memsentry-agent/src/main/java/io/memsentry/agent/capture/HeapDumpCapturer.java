package io.memsentry.agent.capture;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.memsentry.agent.util.Log;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Captures an {@code .hprof} heap dump on demand via {@link HotSpotDiagnosticMXBean},
 * so that when MemSentry flags a leak there is forensic evidence to open in Eclipse MAT
 * or VisualVM. A cooldown prevents a sustained leak from dumping on every tick (each dump
 * is large and forces a stop-the-world).
 */
public final class HeapDumpCapturer {

    private final Path dir;
    private final long cooldownMillis;
    private final HotSpotDiagnosticMXBean bean;
    // 0 == never dumped. (Long.MIN_VALUE would overflow `now - lastDumpAt` and wrongly
    // suppress the very first dump.)
    private long lastDumpAt = 0;

    public HeapDumpCapturer(String dir, Duration cooldown) {
        this.dir = Path.of(dir);
        this.cooldownMillis = cooldown.toMillis();
        this.bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    }

    /**
     * Writes a live-objects-only heap dump, unless still within the cooldown window.
     *
     * @return the dump path, or empty if skipped (cooldown) or failed
     */
    public synchronized Optional<String> capture() {
        long now = System.currentTimeMillis();
        if (now - lastDumpAt < cooldownMillis) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve("memsentry-" + now + ".hprof").toAbsolutePath();
            // dumpHeap refuses to overwrite, so the timestamped name is important.
            bean.dumpHeap(target.toString(), /* live */ true);
            lastDumpAt = now;
            return Optional.of(target.toString());
        } catch (Throwable t) {
            Log.error("heap dump capture failed", t);
            return Optional.empty();
        }
    }
}
