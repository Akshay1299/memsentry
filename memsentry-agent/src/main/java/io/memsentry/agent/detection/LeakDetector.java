package io.memsentry.agent.detection;

import io.memsentry.agent.AgentConfig;
import io.memsentry.agent.model.LeakSuspect;
import io.memsentry.agent.sampling.ClassCount;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Turns a stream of class histograms into ranked leak suspects.
 *
 * <p>For each class it keeps a bounded time series of retained bytes and fits a line
 * (see {@link LinearRegression}). A class is flagged as a leak when, over the window, it
 * is <em>simultaneously</em>:
 * <ul>
 *   <li>growing fast enough — slope ≥ {@code mingrowth} (MB/min), and</li>
 *   <li>growing consistently — R² ≥ {@code minr2} (the rise is a sustained trend, not a blip), and</li>
 *   <li>actually large — current retained bytes ≥ {@code minbytes} (ignore trivia).</li>
 * </ul>
 *
 * <p>Requiring all three together is what keeps false positives down: a cache that warms
 * up and plateaus fails the slope test; a sawtooth GC pattern fails R²; a tiny growing set
 * fails the floor. Not thread-safe — driven solely by the single sampler thread.
 */
public final class LeakDetector {

    /** Minimum samples before a class is eligible for a verdict. */
    private static final int MIN_SAMPLES = 4;
    /** How many suspects to surface to the UI. */
    private static final int TOP_K = 15;
    /** Bytes below which a positive slope is "watching", not yet leaking. */
    private static final long WATCH_FLOOR = 512 * 1024;

    private final AgentConfig config;
    private final LongSupplier clock;
    private final Map<String, Series> seriesByClass = new HashMap<>();
    private long tick = 0;

    public LeakDetector(AgentConfig config) {
        this(config, System::currentTimeMillis);
    }

    /** Test seam: supply a controllable clock so growth can be simulated over "time". */
    public LeakDetector(AgentConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
    }

    /** Feeds a fresh histogram into the detector and returns the current ranked suspects. */
    public List<LeakSuspect> update(List<ClassCount> histogram) {
        tick++;
        long now = clock.getAsLong();
        for (ClassCount cc : histogram) {
            seriesByClass
                    .computeIfAbsent(cc.className(), k -> new Series(config.window()))
                    .add(now, cc.bytes(), cc.instances(), tick);
        }
        pruneStale();
        return analyze();
    }

    /** Drop classes we haven't seen for a full window so the map can't grow unbounded. */
    private void pruneStale() {
        long cutoff = tick - config.window();
        Iterator<Series> it = seriesByClass.values().iterator();
        while (it.hasNext()) {
            if (it.next().lastTick < cutoff) {
                it.remove();
            }
        }
    }

    private List<LeakSuspect> analyze() {
        List<LeakSuspect> suspects = new ArrayList<>();
        for (Map.Entry<String, Series> e : seriesByClass.entrySet()) {
            Series s = e.getValue();
            if (s.size() < MIN_SAMPLES) {
                continue;
            }

            double[] xs = s.secondsFromStart();
            double[] ys = s.bytesAsDoubles();
            LinearRegression reg = LinearRegression.fit(xs, ys);

            long currentBytes = s.lastBytes();
            // Only consider classes that are actually growing and non-trivial.
            if (reg.slope() <= 0 || currentBytes < WATCH_FLOOR) {
                continue;
            }

            boolean leak = reg.slope() >= config.minGrowthBytesPerSec()
                    && reg.rSquared() >= config.minRSquared()
                    && currentBytes >= config.minBytesFloor();

            suspects.add(new LeakSuspect(
                    e.getKey(),
                    currentBytes,
                    s.lastInstances(),
                    reg.slope(),
                    reg.rSquared(),
                    s.size(),
                    leak,
                    s.recentBytes(),
                    null));
        }

        // Rank by how alarming the growth is: fast AND confident floats to the top.
        suspects.sort(Comparator.comparingDouble(
                (LeakSuspect ls) -> ls.growthBytesPerSec() * ls.rSquared()).reversed());

        return suspects.size() > TOP_K ? new ArrayList<>(suspects.subList(0, TOP_K)) : suspects;
    }

    /** A bounded retained-bytes time series for one class. */
    private static final class Series {
        private final int capacity;
        private final Deque<long[]> points = new ArrayDeque<>(); // [timeMillis, bytes, instances]
        long lastTick;

        Series(int capacity) {
            this.capacity = capacity;
        }

        void add(long timeMillis, long bytes, long instances, long tick) {
            points.addLast(new long[]{timeMillis, bytes, instances});
            while (points.size() > capacity) {
                points.removeFirst();
            }
            this.lastTick = tick;
        }

        int size() {
            return points.size();
        }

        long lastBytes() {
            return points.peekLast()[1];
        }

        long lastInstances() {
            return points.peekLast()[2];
        }

        double[] secondsFromStart() {
            long t0 = points.peekFirst()[0];
            double[] xs = new double[points.size()];
            int i = 0;
            for (long[] p : points) {
                xs[i++] = (p[0] - t0) / 1000.0;
            }
            return xs;
        }

        double[] bytesAsDoubles() {
            double[] ys = new double[points.size()];
            int i = 0;
            for (long[] p : points) {
                ys[i++] = p[1];
            }
            return ys;
        }

        List<Long> recentBytes() {
            List<Long> out = new ArrayList<>(points.size());
            for (long[] p : points) {
                out.add(p[1]);
            }
            return out;
        }
    }
}
