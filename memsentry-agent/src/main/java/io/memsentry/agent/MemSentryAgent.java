package io.memsentry.agent;

import io.memsentry.agent.util.Log;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for the MemSentry Java agent.
 *
 * <ul>
 *   <li>{@link #premain} runs when the agent is attached at JVM launch
 *       ({@code -javaagent:memsentry-agent.jar=...}).</li>
 *   <li>{@link #agentmain} runs when the agent is attached dynamically to an
 *       already-running JVM via the Attach API.</li>
 * </ul>
 *
 * <p>Both paths funnel into {@link #bootstrap}, which guarantees that any failure
 * inside the agent is swallowed — a monitoring agent must never take down the
 * application it is observing.
 */
public final class MemSentryAgent {

    private MemSentryAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        bootstrap(agentArgs, inst, "premain");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        bootstrap(agentArgs, inst, "agentmain");
    }

    private static void bootstrap(String agentArgs, Instrumentation inst, String mode) {
        try {
            AgentConfig config = AgentConfig.parse(agentArgs);
            Log.setVerbose(config.verbose());
            Log.info("starting (mode=" + mode + ", " + config + ")");
            MemSentry.start(config);
            Log.info("started — watching heap");
        } catch (Throwable t) {
            // Never propagate: a failed agent must leave the host application running.
            Log.error("failed to start; target application is unaffected", t);
        }
    }
}
