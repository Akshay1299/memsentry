package io.memsentry.agent;

import io.memsentry.agent.util.Log;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent engine. Phase 1 establishes the lifecycle: a single daemon-threaded
 * scheduler that periodically samples heap usage and logs a heartbeat. Later phases
 * grow {@link #sample()} into class-histogram sampling, growth detection, JFR-based
 * leak attribution, heap-dump capture, and alerting.
 */
public final class MemSentry {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private final AgentConfig config;
    private final ScheduledExecutorService scheduler;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    private MemSentry(AgentConfig config) {
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
    }

    /** Idempotent: a second attach (e.g. dynamic on top of premain) is a no-op. */
    public static void start(AgentConfig config) {
        if (!STARTED.compareAndSet(false, true)) {
            Log.warn("already started; ignoring duplicate attach");
            return;
        }
        new MemSentry(config).run();
    }

    private void run() {
        long intervalMs = config.sampleInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::sample, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(scheduler::shutdownNow, "memsentry-shutdown"));
    }

    private void sample() {
        try {
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            long usedMb = bytesToMb(heap.getUsed());
            long committedMb = bytesToMb(heap.getCommitted());
            long maxMb = bytesToMb(heap.getMax());
            double pct = heap.getMax() > 0
                    ? (100.0 * heap.getUsed() / heap.getMax())
                    : Double.NaN;
            Log.info(String.format(
                    "heap used=%dMB committed=%dMB max=%dMB (%.1f%% of max)",
                    usedMb, committedMb, maxMb, pct));
        } catch (Throwable t) {
            // Keep the scheduler alive even if one sample fails.
            Log.error("sample failed", t);
        }
    }

    private static long bytesToMb(long bytes) {
        return bytes < 0 ? bytes : bytes / (1024 * 1024);
    }

    private static ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "memsentry-sampler");
            t.setDaemon(true);
            return t;
        };
    }
}
