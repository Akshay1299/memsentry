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
        assertEquals(Duration.ofSeconds(10), c.sampleInterval());
        assertFalse(c.verbose());
    }

    @Test
    void parsesKnownKeys() {
        AgentConfig c = AgentConfig.parse("interval=5s,verbose=true");
        assertEquals(Duration.ofSeconds(5), c.sampleInterval());
        assertTrue(c.verbose());
    }

    @Test
    void ignoresUnknownKeysAndWhitespace() {
        AgentConfig c = AgentConfig.parse(" foo=bar , interval=250ms , baz ");
        assertEquals(Duration.ofMillis(250), c.sampleInterval());
        assertFalse(c.verbose());
    }

    @Test
    void malformedDurationFallsBackToDefault() {
        AgentConfig c = AgentConfig.parse("interval=notanumber");
        assertEquals(Duration.ofSeconds(10), c.sampleInterval());
    }

    @Test
    void durationUnits() {
        assertEquals(Duration.ofMillis(500), AgentConfig.parseDuration("500ms", Duration.ZERO));
        assertEquals(Duration.ofSeconds(30), AgentConfig.parseDuration("30s", Duration.ZERO));
        assertEquals(Duration.ofMinutes(2), AgentConfig.parseDuration("2m", Duration.ZERO));
        assertEquals(Duration.ofSeconds(15), AgentConfig.parseDuration("15", Duration.ZERO));
    }
}
