package io.memsentry.agent;

import io.memsentry.agent.alert.Alert;
import io.memsentry.agent.alert.AlertSink;
import io.memsentry.agent.alert.CompositeAlertSink;
import io.memsentry.agent.alert.LogAlertSink;
import io.memsentry.agent.alert.WebhookAlertSink;
import io.memsentry.agent.capture.HeapDumpCapturer;
import io.memsentry.agent.detection.LeakDetector;
import io.memsentry.agent.jfr.JfrLeakProfiler;
import io.memsentry.agent.model.AllocationHotspot;
import io.memsentry.agent.model.HealthStatus;
import io.memsentry.agent.model.HeapPoint;
import io.memsentry.agent.model.LeakSuspect;
import io.memsentry.agent.sampling.ClassCount;
import io.memsentry.agent.sampling.ClassHistogramSampler;
import io.memsentry.agent.sampling.HeapSampler;
import io.memsentry.agent.state.StateStore;
import io.memsentry.agent.http.DashboardServer;
import io.memsentry.agent.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The agent engine. Wires the pipeline and drives it on two cadences:
 *
 * <ul>
 *   <li><b>heap tick</b> (fast, cheap): sample overall heap usage and republish.</li>
 *   <li><b>histogram tick</b> (slow, forces GC): sample the live class histogram, run
 *       leak detection, attribute suspects to allocation sites via JFR, and — on a newly
 *       detected leak — capture a heap dump and fire alerts.</li>
 * </ul>
 *
 * <p>Everything runs on daemon threads and every tick is wrapped so a failure degrades the
 * agent rather than the host application.
 */
public final class MemSentry {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final int HEAP_HISTORY = 120;
    private static final int EVENT_LOG = 100;
    private static final int HOTSPOT_TOP_N = 12;

    private final AgentConfig config;
    private final StateStore store;
    private final HeapSampler heapSampler = new HeapSampler();
    private final ClassHistogramSampler histogramSampler = new ClassHistogramSampler();
    private final LeakDetector detector;
    private final JfrLeakProfiler jfr;
    private final HeapDumpCapturer capturer;
    private final AlertSink alertSink;
    private final ScheduledExecutorService scheduler;

    private DashboardServer server;
    private Set<String> activeLeaks = new HashSet<>();

    private MemSentry(AgentConfig config) {
        this.config = config;
        this.store = new StateStore(HEAP_HISTORY, EVENT_LOG);
        this.detector = new LeakDetector(config);
        this.jfr = config.jfrEnabled() ? new JfrLeakProfiler() : null;
        this.capturer = new HeapDumpCapturer(config.dumpDir(), config.dumpCooldown());
        this.alertSink = buildAlertSink(config);
        this.scheduler = Executors.newScheduledThreadPool(2, daemonFactory());
    }

    /** Idempotent: a second attach is a no-op. */
    public static void start(AgentConfig config) {
        if (!STARTED.compareAndSet(false, true)) {
            Log.warn("already started; ignoring duplicate attach");
            return;
        }
        new MemSentry(config).run();
    }

    private void run() {
        store.addEvent("INFO", "MemSentry started (" + config + ")");

        if (jfr != null) {
            jfr.start();
        }
        if (!histogramSampler.isAvailable()) {
            store.addEvent("WARN", "Class histogram unavailable; leak detection limited to heap trend");
        }
        startDashboard();

        long heapMs = config.heapInterval().toMillis();
        long histMs = config.histogramInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::heapTick, 0, heapMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::histogramTick, histMs, histMs, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "memsentry-shutdown"));
    }

    private void startDashboard() {
        if (config.httpPort() <= 0) {
            return;
        }
        try {
            server = new DashboardServer(config.httpPort(), store);
            server.start();
        } catch (Throwable t) {
            Log.error("dashboard failed to start on port " + config.httpPort(), t);
        }
    }

    private void heapTick() {
        try {
            HeapPoint p = heapSampler.sample();
            store.onHeap(p);
            store.onCommitted(heapSampler.committedBytes());
            store.publish();
        } catch (Throwable t) {
            Log.error("heap tick failed", t);
        }
    }

    private void histogramTick() {
        try {
            if (!histogramSampler.isAvailable()) {
                return;
            }
            List<ClassCount> histogram = histogramSampler.sample();
            if (histogram.isEmpty()) {
                store.publish();
                return;
            }

            List<LeakSuspect> suspects = detector.update(histogram);
            List<AllocationHotspot> hotspots = (jfr != null) ? jfr.sample(HOTSPOT_TOP_N) : List.of();
            suspects = attribute(suspects, hotspots);

            store.setSuspects(suspects);
            store.setHotspots(hotspots);
            store.setStatus(computeStatus(suspects));
            handleNewLeaks(suspects);
            store.publish();
        } catch (Throwable t) {
            Log.error("histogram tick failed", t);
        }
    }

    /** Enriches suspects with the allocation site JFR attributes to the same class. */
    private static List<LeakSuspect> attribute(List<LeakSuspect> suspects,
                                               List<AllocationHotspot> hotspots) {
        if (hotspots.isEmpty()) {
            return suspects;
        }
        // hotspots are sorted by sample count desc; attribute only to real application
        // frames so we never mislabel a leak with a JDK-internal site.
        Map<String, String> siteByClass = new HashMap<>();
        for (AllocationHotspot h : hotspots) {
            if (JfrLeakProfiler.isApplicationFrame(h.topFrame())) {
                siteByClass.putIfAbsent(h.className(), h.topFrame());
            }
        }
        List<LeakSuspect> out = new ArrayList<>(suspects.size());
        for (LeakSuspect s : suspects) {
            String site = siteByClass.get(s.className());
            if (site != null && s.allocationSite() == null) {
                out.add(new LeakSuspect(s.className(), s.currentBytes(), s.currentInstances(),
                        s.growthBytesPerSec(), s.rSquared(), s.samples(), s.leak(),
                        s.seriesBytes(), site));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static HealthStatus computeStatus(List<LeakSuspect> suspects) {
        for (LeakSuspect s : suspects) {
            if (s.leak()) {
                return HealthStatus.LEAK_SUSPECTED;
            }
        }
        return suspects.isEmpty() ? HealthStatus.HEALTHY : HealthStatus.WATCHING;
    }

    /** Fires a dump + alert once per class that newly crosses the leak threshold. */
    private void handleNewLeaks(List<LeakSuspect> suspects) {
        List<LeakSuspect> leaks = suspects.stream().filter(LeakSuspect::leak).toList();
        Set<String> currentLeaks = new HashSet<>();
        for (LeakSuspect s : leaks) {
            currentLeaks.add(s.className());
        }

        Set<String> newLeaks = new HashSet<>(currentLeaks);
        newLeaks.removeAll(activeLeaks);

        if (!newLeaks.isEmpty()) {
            store.addEvent("LEAK", "Leak suspected: " + String.join(", ", newLeaks));

            String dumpPath = null;
            if (config.autoDump()) {
                Optional<String> dump = capturer.capture();
                if (dump.isPresent()) {
                    dumpPath = dump.get();
                    store.recordDump(dumpPath);
                    store.addEvent("DUMP", "Heap dump captured: " + dumpPath);
                }
            }

            alertSink.send(new Alert("Memory leak suspected", leaks, dumpPath));
            store.addEvent("ALERT", "Alert dispatched for " + newLeaks.size()
                    + " class" + (newLeaks.size() == 1 ? "" : "es"));
        }

        activeLeaks = currentLeaks;
    }

    private void shutdown() {
        try {
            scheduler.shutdownNow();
        } catch (Throwable ignored) {
            // best-effort
        }
        if (server != null) {
            server.stop();
        }
        if (jfr != null) {
            jfr.stop();
        }
    }

    private static AlertSink buildAlertSink(AgentConfig config) {
        List<AlertSink> sinks = new ArrayList<>();
        sinks.add(new LogAlertSink());
        if (config.hasWebhook()) {
            sinks.add(new WebhookAlertSink(config.webhookUrl()));
        }
        return new CompositeAlertSink(sinks);
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "memsentry-sampler-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
