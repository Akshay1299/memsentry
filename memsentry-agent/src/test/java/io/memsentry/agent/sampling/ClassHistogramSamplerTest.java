package io.memsentry.agent.sampling;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassHistogramSamplerTest {

    private static final String SAMPLE = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:         52341        9874560  [B (java.base@21)
               2:          1203         384960  java.util.HashMap$Node (java.base@21)
               3:           512          16384  com.app.SessionCache$Entry
            Total         54056       10275904
            """;

    @Test
    void parsesDataRows() {
        List<ClassCount> rows = ClassHistogramSampler.parse(SAMPLE);
        assertEquals(3, rows.size(), "header, separator and Total must be skipped");

        ClassCount first = rows.get(0);
        assertEquals("[B", first.className());
        assertEquals(52341, first.instances());
        assertEquals(9874560, first.bytes());
    }

    @Test
    void keepsApplicationClassNames() {
        List<ClassCount> rows = ClassHistogramSampler.parse(SAMPLE);
        Optional<ClassCount> entry = rows.stream()
                .filter(r -> r.className().equals("com.app.SessionCache$Entry"))
                .findFirst();
        assertTrue(entry.isPresent());
        assertEquals(512, entry.get().instances());
    }

    @Test
    void handlesEmptyAndNull() {
        assertTrue(ClassHistogramSampler.parse(null).isEmpty());
        assertTrue(ClassHistogramSampler.parse("").isEmpty());
        assertTrue(ClassHistogramSampler.parse("no data here").isEmpty());
    }
}
