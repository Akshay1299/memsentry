package io.memsentry.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void defaultsWhenNoArgs() {
        AgentConfig c = AgentConfig.parse(null);
        assertEquals(Duration.ofSeconds(10), c.heapInterval());
        assertEquals(Duration.ofSeconds(30), c.histogramInterval());
        assertEquals(7077, c.httpPort());
        assertTrue(c.autoDump());
        assertTrue(c.jfrEnabled());
        assertFalse(c.verbose());
        assertFalse(c.hasWebhook());
    }

    @Test
    void parsesKnownKeys() {
        AgentConfig c = AgentConfig.parse("interval=5s,histogram=15s,port=9090,verbose=true,jfr=off");
        assertEquals(Duration.ofSeconds(5), c.heapInterval());
        assertEquals(Duration.ofSeconds(15), c.histogramInterval());
        assertEquals(9090, c.httpPort());
        assertTrue(c.verbose());
        assertFalse(c.jfrEnabled());
    }

    @Test
    void webhookDetected() {
        AgentConfig c = AgentConfig.parse("webhook=https://hooks.example.com/abc");
        assertTrue(c.hasWebhook());
        assertEquals("https://hooks.example.com/abc", c.webhookUrl());
    }

    @Test
    void mbPerMinConvertedToBytesPerSec() {
        // 60 MB/min == 1 MB/sec == 1048576 bytes/sec
        AgentConfig c = AgentConfig.parse("mingrowth=60");
        assertEquals(1024.0 * 1024.0, c.minGrowthBytesPerSec(), 0.0001);
    }

    @Test
    void ignoresUnknownKeysAndWhitespace() {
        AgentConfig c = AgentConfig.parse(" foo=bar , interval=250ms , baz ");
        assertEquals(Duration.ofMillis(250), c.heapInterval());
        assertFalse(c.verbose());
    }

    @Test
    void malformedValuesFallBackToDefaults() {
        AgentConfig c = AgentConfig.parse("interval=notanumber,port=xyz");
        assertEquals(Duration.ofSeconds(10), c.heapInterval());
        assertEquals(7077, c.httpPort());
    }

    @Test
    void windowHasSafeFloor() {
        AgentConfig c = AgentConfig.parse("window=1");
        assertTrue(c.window() >= 4);
    }

    @Test
    void durationUnits() {
        assertEquals(Duration.ofMillis(500), AgentConfig.parseDuration("500ms", Duration.ZERO));
        assertEquals(Duration.ofSeconds(30), AgentConfig.parseDuration("30s", Duration.ZERO));
        assertEquals(Duration.ofMinutes(2), AgentConfig.parseDuration("2m", Duration.ZERO));
        assertEquals(Duration.ofSeconds(15), AgentConfig.parseDuration("15", Duration.ZERO));
    }
}
