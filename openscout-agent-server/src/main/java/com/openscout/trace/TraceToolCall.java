package com.openscout.trace;

import java.time.Instant;

public record TraceToolCall(
        String toolName,
        String inputSummary,
        String outputSummary,
        long latencyMs,
        String status,
        String errorMessage,
        Instant createdAt
) {
}
