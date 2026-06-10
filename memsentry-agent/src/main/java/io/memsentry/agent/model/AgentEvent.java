package io.memsentry.agent.model;

/**
 * A timestamped entry in the agent's event log (shown in the UI activity feed).
 * {@code level} is one of INFO, WARN, LEAK, DUMP, ALERT.
 */
public record AgentEvent(long epochMillis, String level, String message) {
}
