package io.memsentry.agent.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memsentry.agent.model.AgentEvent;
import io.memsentry.agent.model.AllocationHotspot;
import io.memsentry.agent.model.HeapPoint;
import io.memsentry.agent.model.LeakSuspect;
import io.memsentry.agent.model.Snapshot;
import io.memsentry.agent.state.StateStore;
import io.memsentry.agent.util.Json;
import io.memsentry.agent.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves the MemSentry dashboard from inside the monitored process using the JDK's built-in
 * {@code com.sun.net.httpserver} — no Netty, no Spring, no extra dependency on the host's
 * classpath. Routes:
 *
 * <ul>
 *   <li>{@code GET /}          → the single-file HTML dashboard</li>
 *   <li>{@code GET /api/state} → the latest {@link Snapshot} as JSON (polled by the UI)</li>
 *   <li>{@code GET /healthz}   → liveness probe</li>
 * </ul>
 */
public final class DashboardServer {

    private final int port;
    private final StateStore store;
    private HttpServer server;
    private byte[] dashboardHtml;

    public DashboardServer(int port, StateStore store) {
        this.port = port;
        this.store = store;
    }

    public void start() throws IOException {
        dashboardHtml = loadDashboard();
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/state", this::handleState);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/healthz", this::handleHealthz);
        server.createContext("/", this::handleRoot);
        server.setExecutor(Executors.newFixedThreadPool(2, daemonFactory()));
        server.start();
        Log.info("dashboard available at http://localhost:" + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            respond(ex, 200, "text/html; charset=utf-8", dashboardHtml);
        } else {
            respond(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        byte[] body = toJson(store.snapshot()).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        respond(ex, 200, "application/json; charset=utf-8", body);
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        byte[] body = toPrometheus(store.snapshot()).getBytes(StandardCharsets.UTF_8);
        respond(ex, 200, "text/plain; version=0.0.4; charset=utf-8", body);
    }

    private void handleHealthz(HttpExchange ex) throws IOException {
        respond(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
    }

    /** Prometheus exposition format, so existing Grafana/Prometheus stacks can scrape MemSentry. */
    static String toPrometheus(Snapshot s) {
        StringBuilder sb = new StringBuilder(512);
        gauge(sb, "memsentry_heap_used_bytes", "Heap bytes in use", s.heapUsedBytes());
        gauge(sb, "memsentry_heap_committed_bytes", "Heap bytes committed", s.heapCommittedBytes());
        gauge(sb, "memsentry_heap_max_bytes", "Heap max bytes", s.heapMaxBytes());

        long leakCount = s.suspects().stream().filter(LeakSuspect::leak).count();
        gauge(sb, "memsentry_suspect_count", "Classes growing suspiciously", s.suspects().size());
        gauge(sb, "memsentry_leak_count", "Classes over the leak threshold", leakCount);
        gauge(sb, "memsentry_heap_dumps_total", "Heap dumps captured", s.totalDumps());

        sb.append("# HELP memsentry_class_growth_bytes_per_second Per-class retained-byte growth rate\n");
        sb.append("# TYPE memsentry_class_growth_bytes_per_second gauge\n");
        for (LeakSuspect ls : s.suspects()) {
            sb.append("memsentry_class_growth_bytes_per_second{class=\"")
                    .append(escapeLabel(ls.className())).append("\",leak=\"").append(ls.leak())
                    .append("\"} ").append(Math.round(ls.growthBytesPerSec())).append('\n');
        }

        sb.append("# HELP memsentry_class_retained_bytes Per-class retained bytes (latest sample)\n");
        sb.append("# TYPE memsentry_class_retained_bytes gauge\n");
        for (LeakSuspect ls : s.suspects()) {
            sb.append("memsentry_class_retained_bytes{class=\"")
                    .append(escapeLabel(ls.className())).append("\"} ")
                    .append(ls.currentBytes()).append('\n');
        }
        return sb.toString();
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static String escapeLabel(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** Maps an immutable snapshot to the JSON shape the dashboard consumes. */
    static String toJson(Snapshot s) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", s.epochMillis());
        root.put("heapUsed", s.heapUsedBytes());
        root.put("heapCommitted", s.heapCommittedBytes());
        root.put("heapMax", s.heapMaxBytes());
        root.put("status", s.status().name());
        root.put("totalDumps", s.totalDumps());
        root.put("lastDumpPath", s.lastDumpPath());

        List<Object> history = new ArrayList<>();
        for (HeapPoint p : s.heapHistory()) {
            history.add(List.of(p.epochMillis(), p.usedBytes(), p.maxBytes()));
        }
        root.put("heapHistory", history);

        List<Object> suspects = new ArrayList<>();
        for (LeakSuspect ls : s.suspects()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("className", ls.className());
            m.put("currentBytes", ls.currentBytes());
            m.put("instances", ls.currentInstances());
            m.put("growthMbPerMin", ls.growthMbPerMin());
            m.put("rSquared", ls.rSquared());
            m.put("samples", ls.samples());
            m.put("leak", ls.leak());
            m.put("series", ls.seriesBytes());
            m.put("allocationSite", ls.allocationSite());
            suspects.add(m);
        }
        root.put("suspects", suspects);

        List<Object> hotspots = new ArrayList<>();
        for (AllocationHotspot h : s.hotspots()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("className", h.className());
            m.put("samples", h.sampleCount());
            m.put("topFrame", h.topFrame());
            m.put("stack", h.stack());
            hotspots.add(m);
        }
        root.put("hotspots", hotspots);

        List<Object> events = new ArrayList<>();
        for (AgentEvent e : s.events()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.epochMillis());
            m.put("level", e.level());
            m.put("message", e.message());
            events.add(m);
        }
        root.put("events", events);

        return Json.write(root);
    }

    private byte[] loadDashboard() {
        try (var in = DashboardServer.class.getResourceAsStream("dashboard.html")) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            Log.error("failed to load dashboard.html resource", e);
        }
        return "<h1>MemSentry</h1><p>dashboard.html resource missing</p>"
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "memsentry-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
