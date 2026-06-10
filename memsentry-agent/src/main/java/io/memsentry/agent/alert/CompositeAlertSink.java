package io.memsentry.agent.alert;

import io.memsentry.agent.util.Log;

import java.util.List;

/** Fans an alert out to several sinks; one sink failing never blocks the others. */
public final class CompositeAlertSink implements AlertSink {

    private final List<AlertSink> sinks;

    public CompositeAlertSink(List<AlertSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void send(Alert alert) {
        for (AlertSink sink : sinks) {
            try {
                sink.send(alert);
            } catch (Throwable t) {
                Log.error("alert sink " + sink.getClass().getSimpleName() + " failed", t);
            }
        }
    }
}
