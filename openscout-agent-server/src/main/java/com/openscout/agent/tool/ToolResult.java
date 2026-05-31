package com.openscout.agent.tool;

import com.openscout.agent.runtime.PlanStepStatus;

public record ToolResult(
        PlanStepStatus status,
        String outputSummary,
        String errorSummary,
        long latencyMs
) {

    public static ToolResult success(String outputSummary) {
        return new ToolResult(PlanStepStatus.SUCCESS, outputSummary, null, 0);
    }

    public static ToolResult recoverableFailure(String outputSummary, String errorSummary) {
        return new ToolResult(PlanStepStatus.FAILED, outputSummary, errorSummary, 0);
    }

    public ToolResult withLatency(long latencyMs) {
        return new ToolResult(status, outputSummary, errorSummary, latencyMs);
    }
}
