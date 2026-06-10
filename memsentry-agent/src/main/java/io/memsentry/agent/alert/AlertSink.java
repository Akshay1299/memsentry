package io.memsentry.agent.alert;

/** A destination for leak alerts. Implementations must never throw. */
public interface AlertSink {
    void send(Alert alert);
}
