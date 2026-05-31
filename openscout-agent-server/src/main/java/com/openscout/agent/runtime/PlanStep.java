package com.openscout.agent.runtime;

public class PlanStep {

    private final String stepId;
    private final String toolName;
    private final String purpose;
    private final String inputSummary;
    private final boolean continueOnFailure;
    private PlanStepStatus status = PlanStepStatus.PENDING;

    public PlanStep(String stepId, String toolName, String purpose, String inputSummary, boolean continueOnFailure) {
        this.stepId = stepId;
        this.toolName = toolName;
        this.purpose = purpose;
        this.inputSummary = inputSummary;
        this.continueOnFailure = continueOnFailure;
    }

    public String getStepId() {
        return stepId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public boolean isContinueOnFailure() {
        return continueOnFailure;
    }

    public PlanStepStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStepStatus status) {
        this.status = status;
    }
}
