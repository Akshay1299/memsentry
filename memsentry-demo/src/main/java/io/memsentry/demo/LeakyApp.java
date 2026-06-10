package io.memsentry.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately leaky app used to exercise the MemSentry agent.
 *
 * <p>It appends a 1&nbsp;MB array to a static list every second and never releases
 * it — the textbook "unbounded collection" leak. Run it under the agent:
 *
 * <pre>java -javaagent:memsentry-agent.jar=interval=5s -Xmx256m -jar leaky.jar</pre>
 *
 * and watch the heartbeat climb until the heap is exhausted.
 */
public final class LeakyApp {

    /** Intentional leak: grows without bound for the lifetime of the process. */
    private static final List<byte[]> LEAK = new ArrayList<>();

    private static final int CHUNK_BYTES = 1024 * 1024; // 1 MB

    private LeakyApp() {
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[demo] LeakyApp started — leaking ~1MB/sec. Ctrl-C to stop.");
        long mbLeaked = 0;
        while (true) {
            LEAK.add(new byte[CHUNK_BYTES]);
            mbLeaked++;
            if (mbLeaked % 5 == 0) {
                System.out.println("[demo] leaked " + mbLeaked + " MB so far");
            }
            Thread.sleep(1000);
        }
    }
}
