# MemSentry

### ▶ [**Live dashboard demo**](https://akshay1299.github.io/memsentry/) — replays a real captured leak (no install)

A zero-config **JVM memory-leak detection agent**. Attach it to any Java service with
`-javaagent:memsentry-agent.jar` — no code changes, no dependencies on your classpath —
and it watches the heap, detects leak signatures, attributes them to the code that
allocated the leaking objects, captures a heap dump, and alerts you **before the process
OOMs**.

> The link above is the actual dashboard served by the agent; on GitHub Pages it detects
> there's no live backend and replays a recorded sequence of real snapshots.

It ships with a built-in live dashboard, a Prometheus `/metrics` endpoint, and a demo app
that leaks on command so you can see it work in under a minute.

---

## Why it's different

Most "memory monitors" just graph heap usage and page you when it crosses a line — too
late, and with no idea *what* leaked. MemSentry answers the harder question:

> `io.app.LeakController$CachedSession` grew monotonically for 2 min
> (slope **352 MB/min**, R² **1.00**), allocated at `LeakController.tick:73`.
> Heap dump captured → `heapdumps/memsentry-…​.hprof`.

It does this by combining two JVM facilities most engineers never touch:

1. **Live class-histogram sampling** (`DiagnosticCommand` MBean, the in-process equivalent
   of `jmap -histo:live`) — a per-class time series of *retained* bytes after a full GC.
2. **JFR `OldObjectSample`** — the JVM's purpose-built leak facility, which samples objects
   that survive in the old generation and records the **stack where they were allocated**.

A statistical detector turns the histogram into ranked suspects; JFR tells you the line
of code behind each one.

## How detection works

For every class, MemSentry keeps a bounded time series of retained bytes and fits a line
(ordinary least squares). A class is flagged as a **leak** only when, over the window, it is
*simultaneously*:

| Signal | Meaning | Default threshold |
|--------|---------|-------------------|
| **slope** ≥ `mingrowth` | growing fast enough | 1 MB/min |
| **R²** ≥ `minr2` | growing *consistently* (a trend, not a GC sawtooth) | 0.85 |
| **retained** ≥ `minbytes` | actually big enough to matter | 5 MB |

Requiring all three together is what keeps false positives down: a cache that warms up and
plateaus fails the slope test; a sawtooth allocation pattern fails R²; trivia fails the floor.

## Architecture

```
                         ┌──────────────── MemSentry agent (in-process, zero deps) ─────────────────┐
  heap tick (10s) ─────▶ │ HeapSampler ─┐                                                            │
                         │              ├─▶ StateStore (immutable Snapshot) ──▶ DashboardServer ──┐  │
  histogram tick (30s) ▶ │ ClassHistogramSampler ─▶ LeakDetector ─▶ suspects                      │  │
                         │              JfrLeakProfiler ─▶ allocation hotspots ─┘  (attribution)  │  │
                         │              on new leak ─▶ HeapDumpCapturer + AlertSink (log/webhook)  │  │
                         └────────────────────────────────────────────────────────────────────────┘  │
                                                          ▼ http (com.sun.net.httpserver)             │
                                       GET /  ·  /api/state (JSON)  ·  /metrics (Prometheus)  ·  /healthz
```

Single-writer/lock-free state, daemon threads only, and every tick wrapped so a failure
degrades the agent — never the host application. The agent has **zero runtime
dependencies**: HTTP, JSON, JFR, MBeans, and heap dumps are all JDK built-ins.

## Quick start

```bash
./gradlew build

# Run the demo app under the agent (demo on 8099, dashboard on 7077)
PORT=8099 java -javaagent:memsentry-agent/build/libs/memsentry-agent-0.1.0-SNAPSHOT.jar=histogram=10s \
     -Xmx512m -cp memsentry-demo/build/libs/memsentry-demo-0.1.0-SNAPSHOT.jar io.memsentry.demo.DemoApp
```

Then open:
- **Demo control panel** → http://localhost:8099 — click "Leak: unbounded Cache"
- **MemSentry dashboard** → http://localhost:7077 — watch it flip to **Leak Suspected**

### With Docker (app + Prometheus + Grafana)

```bash
docker compose up --build
# dashboard :7077 · demo :8099 · Prometheus :9090 · Grafana :3000
```

## Attaching to your own service

```bash
java -javaagent:/path/memsentry-agent.jar=histogram=30s,port=7077,webhook=https://hooks.slack.com/... \
     -jar your-service.jar
```

### Configuration (`-javaagent:memsentry-agent.jar=key=val,...`)

| Key | Default | Description |
|-----|---------|-------------|
| `interval` | `10s` | heap heartbeat cadence |
| `histogram` | `30s` | live class-histogram cadence (forces a full GC) |
| `window` | `12` | samples kept per class for the regression |
| `mingrowth` | `1` | leak threshold, MB/min |
| `minr2` | `0.85` | minimum R² to call growth a trend |
| `minbytes` | `5` | minimum retained MB to consider |
| `port` | `7077` | dashboard/metrics port (`0` disables) |
| `autodump` | `true` | capture a heap dump on a new leak |
| `dumpdir` | `heapdumps` | where dumps are written |
| `dumpcooldown` | `10m` | minimum gap between dumps |
| `webhook` | — | Slack/generic webhook URL for alerts |
| `jfr` | `true` | enable JFR allocation-site attribution |
| `verbose` | `false` | debug logging |

## Endpoints

| Path | Purpose |
|------|---------|
| `GET /` | live dashboard (heap trend, ranked suspects, JFR hotspots, activity feed) |
| `GET /api/state` | full state snapshot as JSON |
| `GET /metrics` | Prometheus exposition (heap, suspect/leak counts, per-class growth) |
| `GET /healthz` | liveness probe |

## Design decisions worth calling out

- **Zero-dependency agent.** An agent rides on the host's classpath; bundling libraries
  risks version clashes. Everything uses JDK built-ins.
- **The agent never kills the host.** Every entry point and tick swallows `Throwable`.
- **Honest attribution.** A suspect only shows an allocation site when JFR points at *your*
  code; JDK-internal and the agent's own allocations are filtered out, so it never mislabels.
- **Live histogram on a slow cadence.** It forces a full GC (the correct signal for
  *retained* growth), so it runs infrequently and is documented as the heaviest operation.

## Status

| Phase | Scope | State |
|------|-------|-------|
| 1 | Gradle scaffold + agent lifecycle + heap heartbeat | ✅ |
| 2 | Class-histogram sampler + monotonic-growth detector | ✅ |
| 3 | JFR old-object-sample leak attribution | ✅ |
| 4 | Auto heap-dump capture + Slack/webhook alerting | ✅ |
| 5 | Dashboard + Prometheus + leak-injection demo app | ✅ |
| 6 | Docker / Compose / overhead benchmarks | 🚧 |

## Requirements

- JDK 21 (toolchain pinned in `gradle.properties`)
- Gradle 9.x (wrapper included)
