package com.openscout.agent.run;

public record AgentRunCreateResponse(
        String runId,
        String traceId,
        AgentRunStatus status,
        String eventsUrl
) {
}
