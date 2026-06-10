package io.memsentry.agent.model;

/** Overall verdict surfaced on the dashboard banner. */
public enum HealthStatus {
    /** No class is growing suspiciously. */
    HEALTHY,
    /** At least one class is trending up but below the leak threshold. */
    WATCHING,
    /** One or more classes meet the sustained-growth leak criteria. */
    LEAK_SUSPECTED
}
