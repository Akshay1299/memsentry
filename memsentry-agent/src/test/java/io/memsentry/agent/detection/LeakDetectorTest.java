package io.memsentry.agent.detection;

import io.memsentry.agent.AgentConfig;
import io.memsentry.agent.model.LeakSuspect;
import io.memsentry.agent.sampling.ClassCount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeakDetectorTest {

    private static final long MB = 1024 * 1024;

    /** A clock the test advances manually so growth is measured over simulated time. */
    private static final class FakeClock implements LongSupplier {
        long millis = 0;
        void advanceSeconds(long s) { millis += s * 1000; }
        public long getAsLong() { return millis; }
    }

    private static Optional<LeakSuspect> find(List<LeakSuspect> suspects, String name) {
        return suspects.stream().filter(s -> s.className().equals(name)).findFirst();
    }

    @Test
    void flagsSteadilyGrowingClassAsLeak() {
        FakeClock clock = new FakeClock();
        AgentConfig cfg = AgentConfig.parse("mingrowth=1,minr2=0.85,minbytes=1,window=12");
        LeakDetector detector = new LeakDetector(cfg, clock);

        List<LeakSuspect> suspects = List.of();
        long leakBytes = 2 * MB;
        for (int i = 0; i < 8; i++) {
            suspects = detector.update(List.of(
                    new ClassCount("com.app.SessionCache", 1000 + i, leakBytes),
                    new ClassCount("java.lang.String", 5000, 3 * MB)));   // stable
            leakBytes += MB;          // +1 MB each sample
            clock.advanceSeconds(10); // ...every 10s  => ~6 MB/min, well over threshold
        }

        LeakSuspect leak = find(suspects, "com.app.SessionCache").orElseThrow();
        assertTrue(leak.leak(), "steadily growing class should be flagged");
        assertTrue(leak.growthBytesPerSec() > cfg.minGrowthBytesPerSec());
        assertTrue(leak.rSquared() > 0.95);

        // A perfectly stable class is not even a suspect (slope <= 0).
        assertFalse(find(suspects, "java.lang.String").isPresent());
    }

    @Test
    void slowGrowthIsWatchingNotLeak() {
        FakeClock clock = new FakeClock();
        AgentConfig cfg = AgentConfig.parse("mingrowth=10,minr2=0.85,minbytes=1,window=12");
        LeakDetector detector = new LeakDetector(cfg, clock);

        List<LeakSuspect> suspects = List.of();
        long bytes = 2 * MB;
        for (int i = 0; i < 8; i++) {
            suspects = detector.update(List.of(new ClassCount("com.app.Slow", 100, bytes)));
            bytes += 100 * 1024;       // +100 KB / 10s ~= 0.6 MB/min  (< 10 MB/min threshold)
            clock.advanceSeconds(10);
        }

        LeakSuspect s = find(suspects, "com.app.Slow").orElseThrow();
        assertTrue(s.growthBytesPerSec() > 0, "should register positive growth");
        assertFalse(s.leak(), "below mingrowth should be watching, not a leak");
    }

    @Test
    void needsMinimumSamplesBeforeVerdict() {
        FakeClock clock = new FakeClock();
        AgentConfig cfg = AgentConfig.parse("mingrowth=1,minr2=0.85,minbytes=1,window=12");
        LeakDetector detector = new LeakDetector(cfg, clock);

        List<LeakSuspect> after2 = List.of();
        long bytes = 2 * MB;
        for (int i = 0; i < 2; i++) {
            after2 = detector.update(List.of(new ClassCount("com.app.X", 1, bytes)));
            bytes += MB;
            clock.advanceSeconds(10);
        }
        assertEquals(0, after2.size(), "two samples is below MIN_SAMPLES");
    }
}
