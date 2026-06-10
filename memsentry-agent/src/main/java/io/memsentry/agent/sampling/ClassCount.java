package io.memsentry.agent.sampling;

/** One row of a class histogram: a class and its live instance count and retained bytes. */
public record ClassCount(String className, long instances, long bytes) {
}
