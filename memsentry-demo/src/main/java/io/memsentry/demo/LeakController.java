package io.memsentry.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives several classic memory-leak patterns on demand so MemSentry has something to
 * detect. A background timer allocates and <em>retains</em> objects according to the
 * selected {@link Mode}; stopping (optionally) clears the retained references so the heap
 * recovers after a GC — useful for showing the dashboard return to "Healthy".
 */
public final class LeakController {

    public enum Mode { NONE, LIST, MAP, STRINGS }

    private static final int CHUNK = 256 * 1024; // 256 KB per allocation

    // Three textbook leaks: an unbounded list, an unbounded cache, and retained strings.
    private final List<byte[]> list = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, CachedSession> cache = new ConcurrentHashMap<>();
    private final Set<String> strings = ConcurrentHashMap.newKeySet();

    /**
     * A named domain object so the cache leak shows up in the histogram as
     * {@code io.memsentry.demo.LeakController$CachedSession} — far more illustrative
     * than a raw {@code byte[]} when demoing detection and allocation-site attribution.
     */
    private static final class CachedSession {
        final long id;
        final byte[] payload;

        CachedSession(long id, int size) {
            this.id = id;
            this.payload = new byte[size];
        }
    }

    private final AtomicLong counter = new AtomicLong();
    private final ScheduledExecutorService exec;

    private volatile Mode mode = Mode.NONE;
    private volatile int ratePerSec = 4;

    public LeakController() {
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leak-driver");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        Mode m = mode;
        if (m == Mode.NONE) {
            return;
        }
        int perTick = Math.max(1, ratePerSec / 4); // timer fires 4x/sec
        for (int i = 0; i < perTick; i++) {
            long id = counter.incrementAndGet();
            switch (m) {
                case LIST -> list.add(new byte[CHUNK]);
                case MAP -> cache.put(id, new CachedSession(id, CHUNK));
                case STRINGS -> strings.add(buildString(id));
                default -> { }
            }
        }
    }

    private static String buildString(long id) {
        // A unique, non-trivial string so it can't be deduplicated away.
        return ("memsentry-leak-" + id + "-").repeat(1000);
    }

    public void start(Mode mode, int ratePerSec) {
        this.ratePerSec = ratePerSec > 0 ? ratePerSec : 4;
        this.mode = mode;
    }

    public void stop(boolean clear) {
        this.mode = Mode.NONE;
        if (clear) {
            list.clear();
            cache.clear();
            strings.clear();
            System.gc(); // demo only: make the recovery visible quickly
        }
    }

    public Mode mode() {
        return mode;
    }

    public int ratePerSec() {
        return ratePerSec;
    }

    public long retainedBytes() {
        long chunks = (long) list.size() + cache.size();
        long stringBytes = (long) strings.size() * CHUNK; // rough estimate
        return chunks * CHUNK + stringBytes;
    }

    public long allocations() {
        return counter.get();
    }
}
