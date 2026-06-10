package io.memsentry.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A small web service that can leak memory on command, used to exercise the MemSentry
 * agent. Run it under the agent and open both UIs:
 *
 * <pre>
 * java -javaagent:memsentry-agent.jar=histogram=10s,port=7077 -Xmx512m \
 *      -cp memsentry-demo.jar io.memsentry.demo.DemoApp
 *
 * # control panel : http://localhost:8080
 * # MemSentry     : http://localhost:7077
 * </pre>
 */
public final class DemoApp {

    private static final LeakController CONTROLLER = new LeakController();

    private DemoApp() {
    }

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", DemoApp::control);
        server.createContext("/leak/start", DemoApp::start);
        server.createContext("/leak/stop", DemoApp::stop);
        server.createContext("/status", DemoApp::status);
        server.createContext("/healthz", (ex) -> send(ex, 200, "text/plain", "ok"));
        server.setExecutor(null);
        server.start();
        System.out.println("[demo] control panel on http://localhost:" + port
                + " — leak types: list, map, strings");
    }

    /** Port from (in order) the first CLI arg, the {@code PORT} env var, then 8080. */
    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            int p = parseInt(args[0], -1);
            if (p > 0) {
                return p;
            }
        }
        return parseInt(System.getenv("PORT"), 8080);
    }

    private static void start(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        LeakController.Mode mode = parseMode(q.getOrDefault("type", "list"));
        int rate = parseInt(q.get("rate"), 8);
        CONTROLLER.start(mode, rate);
        send(ex, 200, "application/json",
                "{\"mode\":\"" + mode + "\",\"rate\":" + rate + "}");
    }

    private static void stop(HttpExchange ex) throws IOException {
        boolean clear = Boolean.parseBoolean(query(ex).getOrDefault("clear", "true"));
        CONTROLLER.stop(clear);
        send(ex, 200, "application/json", "{\"mode\":\"NONE\",\"cleared\":" + clear + "}");
    }

    private static void status(HttpExchange ex) throws IOException {
        String json = "{"
                + "\"mode\":\"" + CONTROLLER.mode() + "\","
                + "\"rate\":" + CONTROLLER.ratePerSec() + ","
                + "\"retainedMB\":" + (CONTROLLER.retainedBytes() / (1024 * 1024)) + ","
                + "\"allocations\":" + CONTROLLER.allocations()
                + "}";
        send(ex, 200, "application/json", json);
    }

    private static void control(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain", "not found");
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", PAGE);
    }

    private static LeakController.Mode parseMode(String s) {
        try {
            return LeakController.Mode.valueOf(s.trim().toUpperCase());
        } catch (RuntimeException e) {
            return LeakController.Mode.LIST;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static final String PAGE = """
        <!DOCTYPE html><html><head><meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <title>MemSentry Demo</title>
        <style>
          body{margin:0;background:#0b0e14;color:#e6edf3;font-family:system-ui,sans-serif;padding:40px}
          .wrap{max-width:560px;margin:0 auto}
          h1{font-size:22px} p{color:#8b98ad}
          .row{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0}
          button{padding:12px 16px;border-radius:10px;border:1px solid #232c3d;background:#18202f;
            color:#e6edf3;font-size:14px;cursor:pointer}
          button:hover{border-color:#5b9dff}
          button.stop{background:#2a1620;border-color:#ff5c5c;color:#ff5c5c}
          .stat{font-family:ui-monospace,monospace;background:#121723;border:1px solid #232c3d;
            border-radius:10px;padding:16px;margin-top:18px}
          a{color:#5b9dff}
        </style></head><body><div class="wrap">
          <h1>🧪 MemSentry Demo</h1>
          <p>Trigger a leak, then watch <a href="http://localhost:7077" target="_blank">the MemSentry dashboard</a> detect it.</p>
          <div class="row">
            <button onclick="go('list')">Leak: unbounded List</button>
            <button onclick="go('map')">Leak: unbounded Cache</button>
            <button onclick="go('strings')">Leak: retained Strings</button>
          </div>
          <div class="row"><button class="stop" onclick="stop()">Stop &amp; clear</button></div>
          <div class="stat" id="stat">mode: NONE</div>
        </div>
        <script>
          async function go(t){ await fetch('/leak/start?type='+t+'&rate=12'); refresh(); }
          async function stop(){ await fetch('/leak/stop?clear=true'); refresh(); }
          async function refresh(){ const s=await (await fetch('/status')).json();
            document.getElementById('stat').textContent =
              'mode: '+s.mode+'  ·  retained: '+s.retainedMB+' MB  ·  allocations: '+s.allocations; }
          refresh(); setInterval(refresh, 1500);
        </script></body></html>
        """;
}
