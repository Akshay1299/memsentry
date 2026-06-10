package io.memsentry.agent.model;

import java.util.List;

/**
 * An immutable, point-in-time view of everything MemSentry knows. The orchestrator
 * publishes a new {@code Snapshot} into the {@code StateStore} on each tick, and the
 * dashboard HTTP handler serializes whatever the latest one is. Immutability means the
 * sampler thread and the HTTP threads never contend over shared mutable state.
 */
public record Snapshot(
        long epochMillis,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        HealthStatus status,
        List<HeapPoint> heapHistory,
        List<LeakSuspect> suspects,
        List<AllocationHotspot> hotspots,
        List<AgentEvent> events,
        int totalDumps,
        String lastDumpPath) {

    public static Snapshot empty(long now) {
        return new Snapshot(now, 0, 0, 0, HealthStatus.HEALTHY,
                List.of(), List.of(), List.of(), List.of(), 0, null);
    }
}
