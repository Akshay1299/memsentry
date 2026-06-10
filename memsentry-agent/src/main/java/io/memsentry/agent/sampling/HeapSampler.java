package io.memsentry.agent.sampling;

import io.memsentry.agent.model.HeapPoint;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/** Cheap, frequent sampler of overall heap usage via {@link MemoryMXBean}. */
public final class HeapSampler {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    public HeapPoint sample() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long max = heap.getMax(); // -1 if undefined
        return new HeapPoint(System.currentTimeMillis(), heap.getUsed(),
                max < 0 ? heap.getCommitted() : max);
    }

    public long committedBytes() {
        return memoryBean.getHeapMemoryUsage().getCommitted();
    }
}
