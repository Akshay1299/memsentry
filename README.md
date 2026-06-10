# MemSentry

A zero-config **JVM memory-leak detection agent**. Attach it to any Java service with
`-javaagent:memsentry-agent.jar` — no code changes — and it watches the heap, detects
leak signatures, captures evidence (heap dumps + allocation stack traces), and alerts
*before* the process OOMs.

> Built as a deep-dive into JVM internals: JFR old-object sampling, diagnostic MBeans,
> bytecode-free instrumentation, and time-series leak detection.

## Why it's different

Most "memory monitors" just graph heap usage and fire when it crosses a line — which is
too late and tells you nothing about *what* leaked. MemSentry leans on the JVM's own
**JFR `OldObjectSample`** events, which track objects that survive GC and record the
**allocation stack trace and the reference chain keeping them alive**. Combined with a
per-class histogram time series and monotonic-growth detection, MemSentry can say:

> `com.app.SessionCache` grew monotonically for 12 min (slope 4.2 MB/min),
> allocated at `SessionManager.register(SessionManager.java:84)`.

## Status

Under active development. Roadmap:

| Phase | Scope | State |
|------|-------|-------|
| 1 | Gradle scaffold + agent lifecycle + heap heartbeat | ✅ in progress |
| 2 | Class-histogram sampler + monotonic-growth detector | ⬜ |
| 3 | JFR old-object-sample leak attribution | ⬜ |
| 4 | Auto heap-dump capture + Slack/webhook alerting | ⬜ |
| 5 | Prometheus/Grafana + leak-injection demo app | ⬜ |
| 6 | Docker, Cloud Run deploy, overhead benchmarks | ⬜ |

## Build & run (local)

```bash
./gradlew build

# Run the leaky demo app under the agent
java -javaagent:memsentry-agent/build/libs/memsentry-agent-0.1.0-SNAPSHOT.jar=interval=5s,verbose=true \
     -Xmx256m \
     -cp memsentry-demo/build/classes/java/main io.memsentry.demo.LeakyApp
```

## Requirements

- JDK 21 (toolchain pinned in `gradle.properties`)
- Gradle 9.x (wrapper included)

## Modules

- `memsentry-agent` — the `-javaagent` itself (zero runtime dependencies by design).
- `memsentry-demo` — a deliberately leaky app for exercising the agent.
