package io.memsentry.agent.alert;

import io.memsentry.agent.util.Json;
import io.memsentry.agent.util.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Posts the alert as JSON to a webhook URL. The body is {@code {"text": "..."}}, which
 * Slack incoming-webhooks accept directly and most generic webhooks tolerate.
 *
 * <p>Delivery is fire-and-forget on the JDK's async HTTP client so a slow or down endpoint
 * never stalls the sampler thread.
 */
public final class WebhookAlertSink implements AlertSink {

    private final URI endpoint;
    private final HttpClient client;

    public WebhookAlertSink(String url) {
        this.endpoint = URI.create(url);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void send(Alert alert) {
        try {
            String body = Json.write(Map.of("text", alert.renderText()));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        Log.error("webhook delivery failed", ex);
                        return null;
                    });
        } catch (Throwable t) {
            Log.error("webhook alert could not be built", t);
        }
    }
}
