package io.memsentry.agent.model;

import java.util.List;

/**
 * An allocation site implicated by JFR's old-object sampling: a class whose
 * surviving instances were allocated at a particular stack, with the frames that
 * led there. This is what lets MemSentry point at the line of code behind a leak.
 *
 * @param className  class of the sampled surviving objects
 * @param sampleCount number of old-object samples attributed to this site
 * @param topFrame   the most relevant (top non-JDK) frame, for one-line display
 * @param stack      the allocation stack frames (top→bottom)
 */
public record AllocationHotspot(
        String className,
        long sampleCount,
        String topFrame,
        List<String> stack) {
}
