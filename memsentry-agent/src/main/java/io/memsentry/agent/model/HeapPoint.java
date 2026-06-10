package io.memsentry.agent.model;

/** A single heap-usage sample, used to draw the heap trend sparkline. */
public record HeapPoint(long epochMillis, long usedBytes, long maxBytes) {
}
