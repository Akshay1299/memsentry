package io.memsentry.agent.jfr;

import io.memsentry.agent.model.AllocationHotspot;
import io.memsentry.agent.util.Log;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps JFR's {@code jdk.OldObjectSample} event — the JVM's purpose-built leak-detection
 * facility. It samples objects that have survived in the heap and records both the object's
 * type and the <b>stack trace where it was allocated</b>. By dumping a snapshot of the
 * recording and aggregating those samples by class, MemSentry can attribute a growing class
 * to the exact code path that created the surviving instances.
 *
 * <p>Entirely optional and fully isolated: if JFR is unavailable or an event can't be
 * parsed, it degrades silently to "no attribution" rather than affecting the core detector.
 */
public final class JfrLeakProfiler {

    private static final String OLD_OBJECT_SAMPLE = "jdk.OldObjectSample";
    private static final int MAX_FRAMES = 12;

    private Recording recording;
    private volatile boolean active;

    public boolean isActive() {
        return active;
    }

    public void start() {
        try {
            Recording r = new Recording();
            // Fresh recording has no events enabled; we turn on only old-object sampling
            // with stack traces, keeping overhead minimal. cutoff=0 keeps all live samples.
            r.enable(OLD_OBJECT_SAMPLE).withStackTrace().with("cutoff", "0 ns");
            r.start();
            this.recording = r;
            this.active = true;
            Log.info("JFR old-object sampling started");
        } catch (Throwable t) {
            this.active = false;
            Log.warn("JFR unavailable; leak attribution disabled (" + t.getMessage() + ")");
        }
    }

    public void stop() {
        if (recording != null) {
            try {
                recording.close();
            } catch (Throwable ignored) {
                // best-effort
            }
        }
        active = false;
    }

    /**
     * Dumps a snapshot of the recording and returns the top allocation hotspots by sample
     * count. Never throws; returns an empty list on any failure.
     */
    public List<AllocationHotspot> sample(int topN) {
        if (!active) {
            return List.of();
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("memsentry-jfr", ".jfr");
            recording.dump(tmp);

            Map<String, Agg> byClass = new HashMap<>();
            try (RecordingFile rf = new RecordingFile(tmp)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent event = rf.readEvent();
                    if (!OLD_OBJECT_SAMPLE.equals(event.getEventType().getName())) {
                        continue;
                    }
                    ingest(event, byClass);
                }
            }

            List<AllocationHotspot> hotspots = new ArrayList<>();
            for (Map.Entry<String, Agg> e : byClass.entrySet()) {
                Agg a = e.getValue();
                hotspots.add(new AllocationHotspot(e.getKey(), a.count, a.topFrame(), a.stack));
            }
            hotspots.sort(Comparator.comparingLong(AllocationHotspot::sampleCount).reversed());
            return hotspots.size() > topN ? hotspots.subList(0, topN) : hotspots;
        } catch (Throwable t) {
            Log.debug("JFR sample failed: " + t);
            return List.of();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Throwable ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static void ingest(RecordedEvent event, Map<String, Agg> byClass) {
        try {
            String className = classNameOf(event);
            if (className == null) {
                return;
            }
            List<String> stack = framesOf(event.getStackTrace());
            // Ignore objects allocated by MemSentry itself — we must not report the
            // agent's own footprint as an application hotspot.
            if (firstAppFrame(stack) != null && isAgentFrame(firstAppFrame(stack))) {
                return;
            }
            Agg agg = byClass.computeIfAbsent(className, k -> new Agg());
            agg.count++;
            // Keep the stack that actually points at application code, if we find one.
            if (agg.stack.isEmpty() || (firstAppFrame(agg.stack) == null && firstAppFrame(stack) != null)) {
                agg.stack = stack;
            }
        } catch (Throwable ignored) {
            // A single unparseable event must not abort the whole sample.
        }
    }

    private static String classNameOf(RecordedEvent event) {
        // OldObjectSample.object is a jdk.types.OldObject whose "type" is the object's class.
        if (!event.hasField("object")) {
            return null;
        }
        Object objField = event.getValue("object");
        if (objField instanceof RecordedObject oldObject && oldObject.hasField("type")) {
            Object type = oldObject.getValue("type");
            if (type instanceof RecordedClass rc) {
                return rc.getName();
            }
        }
        return null;
    }

    private static List<String> framesOf(RecordedStackTrace stack) {
        List<String> frames = new ArrayList<>();
        if (stack == null) {
            return frames;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (!frame.isJavaFrame()) {
                continue;
            }
            RecordedMethod method = frame.getMethod();
            if (method == null) {
                continue;
            }
            String where = method.getType().getName() + "." + method.getName();
            int line = frame.getLineNumber();
            frames.add(line > 0 ? where + ":" + line : where);
            if (frames.size() >= MAX_FRAMES) {
                break;
            }
        }
        return frames;
    }

    /** Mutable accumulator used only within a single {@link #sample} call. */
    private static final class Agg {
        long count;
        List<String> stack = List.of();

        /** First application frame, else the topmost frame, else a placeholder. */
        String topFrame() {
            String app = firstAppFrame(stack);
            if (app != null) {
                return app;
            }
            return stack.isEmpty() ? "(no stack)" : stack.get(0);
        }
    }

    /** First frame that is neither JDK runtime nor MemSentry itself — i.e. real app code. */
    static String firstAppFrame(List<String> stack) {
        for (String f : stack) {
            if (isApplicationFrame(f)) {
                return f;
            }
        }
        return null;
    }

    /** True for frames in application code (used to decide whether to attribute a leak). */
    public static boolean isApplicationFrame(String frame) {
        return !isJdkFrame(frame) && !isAgentFrame(frame);
    }

    private static boolean isAgentFrame(String frame) {
        return frame.startsWith("io.memsentry.agent");
    }

    private static boolean isJdkFrame(String frame) {
        return frame.startsWith("java.")
                || frame.startsWith("jdk.")
                || frame.startsWith("sun.")
                || frame.startsWith("javax.")
                || frame.startsWith("com.sun.");
    }
}
