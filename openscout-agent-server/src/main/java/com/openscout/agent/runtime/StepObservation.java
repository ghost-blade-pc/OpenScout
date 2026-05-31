package com.openscout.agent.runtime;

public record StepObservation(
        String stepId,
        String toolName,
        PlanStepStatus status,
        String outputSummary,
        String errorSummary,
        long latencyMs
) {
}
