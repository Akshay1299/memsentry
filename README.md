# MemSentry

[![Live Demo](https://img.shields.io/badge/Live%20Demo-online-2ee6a6?style=flat-square)](https://akshay1299.github.io/memsentry/)
![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.5-02303A?style=flat-square&logo=gradle)
![Dependencies](https://img.shields.io/badge/runtime%20deps-0-5b9dff?style=flat-square)

**A zero-config JVM memory-leak detection agent.** Attach it to any Java service with
`-javaagent:memsentry-agent.jar` — no code changes, nothing added to your classpath — and
it watches the heap, detects leaks while they grow, points at the line of code that
allocated the leaking objects, captures a heap dump, and alerts you **before the process
OOMs**.

### ▶ [**Open the live dashboard demo**](https://akshay1299.github.io/memsentry/) — replays a real captured leak, no install

> It's the actual dashboard the agent serves; on GitHub Pages (no JVM backend) it detects
> there's no live API and replays a recorded sequence of real snapshots.

---

## Highlights

- 🔌 **Attaches to any JVM** with zero code changes and **zero runtime dependencies** (JDK-only).
- 📈 **Detects leaks statistically** — per-class retained-memory growth + linear-regression
  trend analysis, not a crude "heap is high" threshold.
- 🎯 **Attributes the leak to your code** via JFR old-object sampling (allocation stack).
- 📸 **Auto-captures heap dumps** and **alerts** (Slack/webhook) the moment a leak is confirmed.
- 📊 **Built-in live dashboard** + **Prometheus `/metrics`** — served from the JDK's own HTTP server.
- 🐳 **One-command stack** (`docker compose up`) with Prometheus + Grafana, and a public live demo.
- ⏱️ Verified end-to-end: a real leak is detected in **~12 seconds**.

## Why it's different

Most "memory monitors" just graph heap usage and page you when it crosses a line — too
late, and with no idea *what* leaked. MemSentry answers the harder question:

> `io.app.LeakController$CachedSession` grew steadily for 2 min
> (**+352 MB/min**, R² **1.00**), allocated at `LeakController.tick:73`.
> Heap dump captured → `heapdumps/memsentry-…​.hprof`.

## How detection works

For every class, MemSentry keeps a bounded time series of retained bytes (sampled after GC)
and fits a line. A class is flagged a **leak** only when, over the window, it is *all three*:

| Signal | Meaning | Default |
|--------|---------|---------|
| **slope** ≥ `mingrowth` | growing fast enough | 1 MB/min |
| **R²** ≥ `minr2` | growing *consistently* (a trend, not a GC sawtooth) | 0.85 |
| **retained** ≥ `minbytes` | actually big enough to matter | 5 MB |

Requiring all three keeps false positives down: a cache that warms up and plateaus fails
the slope test; a sawtooth fails R²; trivia fails the floor.

## Architecture

```
                    ┌──────────── MemSentry agent (in-process, zero deps) ────────────┐
 heap tick (10s) ─▶ │ HeapSampler ─┐                                                   │
                    │              ├▶ StateStore (immutable Snapshot) ─▶ DashboardServer
 hist tick (30s) ─▶ │ ClassHistogramSampler ─▶ LeakDetector ─▶ suspects                │
                    │              JfrLeakProfiler ─▶ allocation site (attribution)    │
                    │              on new leak ─▶ HeapDumpCapturer + AlertSink          │
                    └────────────────────────────────────────────────────────────────┘
                                       ▼ http (com.sun.net.httpserver)
                   GET /  ·  /api/state (JSON)  ·  /metrics (Prometheus)  ·  /healthz
```

Single-writer/lock-free state, daemon threads only, and every tick wrapped so a failure
degrades the agent — never the host application.

## Quick start

```bash
./gradlew build

# Demo app under the agent (demo on 8099, dashboard on 7077)
PORT=8099 java -javaagent:memsentry-agent/build/libs/memsentry-agent-0.1.0-SNAPSHOT.jar=histogram=10s \
     -Xmx512m -cp memsentry-demo/build/libs/memsentry-demo-0.1.0-SNAPSHOT.jar io.memsentry.demo.DemoApp
```

Open **http://localhost:8099** → click "Leak: unbounded Cache" → watch
**http://localhost:7077** flip to **Leak Suspected**.

### Full stack with Docker

```bash
docker compose up --build
# dashboard :7077 · demo :8099 · Prometheus :9090 · Grafana :3000
```

## Attaching to your own service

```bash
java -javaagent:/path/memsentry-agent.jar=histogram=30s,port=7077,webhook=https://hooks.slack.com/... \
     -jar your-service.jar
```

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

## Concepts demonstrated

JVM internals · `java.lang.instrument` agents (premain/agentmain) · Java Flight Recorder
(`OldObjectSample`) · platform MBeans (`DiagnosticCommand`, `HotSpotDiagnostic`) · heap-dump
capture & analysis · time-series leak detection (OLS regression) · lock-free concurrency ·
Prometheus/observability · Docker & Compose · GitHub Actions CI/CD · dependency-free design.

## Design decisions

- **Zero-dependency agent.** An agent rides on the host's classpath; bundling libraries
  risks version clashes. Everything uses JDK built-ins.
- **The agent never kills the host.** Every entry point and tick swallows `Throwable`.
- **Honest attribution.** A suspect shows an allocation site only when JFR points at *your*
  code; JDK-internal and the agent's own frames are filtered out, so it never mislabels.
- **Live histogram on a slow cadence.** It forces a full GC (the correct signal for
  *retained* growth), so it runs infrequently and is documented as the heaviest operation.

## Project layout

```
memsentry-agent/   the -javaagent (sampling, detection, jfr, capture, alert, http, state)
memsentry-demo/    a control-panel app that leaks on command (list / cache / strings)
site/              recorded snapshots replayed by the GitHub Pages demo
deploy/            Prometheus + Grafana provisioning
```

## Status

| Phase | Scope | State |
|------|-------|-------|
| 1 | Gradle scaffold + agent lifecycle + heap heartbeat | ✅ |
| 2 | Class-histogram sampler + monotonic-growth detector | ✅ |
| 3 | JFR old-object-sample leak attribution | ✅ |
| 4 | Auto heap-dump capture + Slack/webhook alerting | ✅ |
| 5 | Dashboard + Prometheus + leak-injection demo app | ✅ |
| 6 | Docker / Compose / live demo on GitHub Pages | ✅ |

## Requirements

- JDK 21 (toolchain pinned in `gradle.properties`)
- Gradle 9.x (wrapper included)
