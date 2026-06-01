package com.openscout.agent.event;

import java.time.Instant;

public record AgentEvent(
        String runId,
        String traceId,
        String type,
        String status,
        String stepId,
        String toolName,
        String inputSummary,
        String outputSummary,
        String errorSummary,
        long latencyMs,
        Instant createdAt
) {
}
