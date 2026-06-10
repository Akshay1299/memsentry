package io.memsentry.agent.alert;

import io.memsentry.agent.util.Log;

/** Always-on sink that writes the alert to the agent log. */
public final class LogAlertSink implements AlertSink {

    @Override
    public void send(Alert alert) {
        Log.warn("ALERT\n" + alert.renderText());
    }
}
