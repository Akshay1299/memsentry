package io.memsentry.agent.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void primitives() {
        assertEquals("null", Json.write(null));
        assertEquals("true", Json.write(true));
        assertEquals("42", Json.write(42));
        assertEquals("\"hi\"", Json.write("hi"));
    }

    @Test
    void wholeDoublesAreCompact() {
        assertEquals("3", Json.write(3.0));
        assertEquals("1.5", Json.write(1.5));
    }

    @Test
    void nonFiniteBecomesNull() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    void escapesStrings() {
        assertEquals("\"a\\\"b\\nc\"", Json.write("a\"b\nc"));
    }

    @Test
    void objectAndArrayNesting() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "[B");
        m.put("bytes", 1024);
        m.put("series", List.of(1, 2, 3));
        assertEquals("{\"name\":\"[B\",\"bytes\":1024,\"series\":[1,2,3]}", Json.write(m));
    }
}
