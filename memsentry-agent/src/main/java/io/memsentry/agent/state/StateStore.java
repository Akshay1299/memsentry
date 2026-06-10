package io.memsentry.agent.state;

import io.memsentry.agent.model.AgentEvent;
import io.memsentry.agent.model.AllocationHotspot;
import io.memsentry.agent.model.HealthStatus;
import io.memsentry.agent.model.HeapPoint;
import io.memsentry.agent.model.LeakSuspect;
import io.memsentry.agent.model.Snapshot;
import io.memsentry.agent.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds MemSentry's evolving state and publishes immutable {@link Snapshot}s.
 *
 * <p>Concurrency model: a <b>single writer</b> (the sampler thread) mutates the internal
 * buffers and calls {@link #publish()}; any number of HTTP reader threads call
 * {@link #snapshot()}, which just reads an {@link AtomicReference}. No locks, no contention.
 */
public final class StateStore {

    private final int historyCap;
    private final int eventCap;

    // Mutated only by the sampler thread.
    private final Deque<HeapPoint> heapHistory = new ArrayDeque<>();
    private final Deque<AgentEvent> events = new ArrayDeque<>();
    private List<LeakSuspect> suspects = List.of();
    private List<AllocationHotspot> hotspots = List.of();
    private HealthStatus status = HealthStatus.HEALTHY;
    private long heapUsed;
    private long heapCommitted;
    private long heapMax;
    private int totalDumps;
    private String lastDumpPath;

    private final AtomicReference<Snapshot> current;

    public StateStore(int historyCap, int eventCap) {
        this.historyCap = historyCap;
        this.eventCap = eventCap;
        this.current = new AtomicReference<>(Snapshot.empty(System.currentTimeMillis()));
    }

    public void onHeap(HeapPoint p) {
        heapHistory.addLast(p);
        while (heapHistory.size() > historyCap) {
            heapHistory.removeFirst();
        }
        heapUsed = p.usedBytes();
        heapMax = p.maxBytes();
    }

    public void onCommitted(long committed) {
        heapCommitted = committed;
    }

    public void setSuspects(List<LeakSuspect> suspects) {
        this.suspects = List.copyOf(suspects);
    }

    public void setHotspots(List<AllocationHotspot> hotspots) {
        this.hotspots = List.copyOf(hotspots);
    }

    public void setStatus(HealthStatus status) {
        this.status = status;
    }

    public void addEvent(String level, String message) {
        events.addLast(new AgentEvent(System.currentTimeMillis(), level, message));
        while (events.size() > eventCap) {
            events.removeFirst();
        }
        Log.info("[" + level + "] " + message);
    }

    public void recordDump(String path) {
        totalDumps++;
        lastDumpPath = path;
    }

    /** Builds and publishes a fresh immutable snapshot from the current state. */
    public void publish() {
        // Events newest-first for the UI activity feed.
        List<AgentEvent> eventsNewestFirst = new ArrayList<>(events);
        java.util.Collections.reverse(eventsNewestFirst);

        current.set(new Snapshot(
                System.currentTimeMillis(),
                heapUsed,
                heapCommitted,
                heapMax,
                status,
                List.copyOf(heapHistory),
                suspects,
                hotspots,
                List.copyOf(eventsNewestFirst),
                totalDumps,
                lastDumpPath));
    }

    public Snapshot snapshot() {
        return current.get();
    }
}
