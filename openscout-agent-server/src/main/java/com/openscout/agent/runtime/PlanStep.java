package com.openscout.agent.runtime;

public class PlanStep {

    private final String stepId;
    private final String toolName;
    private final String purpose;
    private final String inputSummary;
    private final boolean continueOnFailure;
    /**
     * 并行组标识。同一组内的连续步骤在 PlanExecutor 中并发执行。
     * {@code null} 表示默认顺序执行。
     */
    private final String parallelGroup;
    private PlanStepStatus status = PlanStepStatus.PENDING;

    public PlanStep(String stepId, String toolName, String purpose, String inputSummary, boolean continueOnFailure) {
        this(stepId, toolName, purpose, inputSummary, continueOnFailure, null);
    }

    public PlanStep(String stepId, String toolName, String purpose, String inputSummary,
                    boolean continueOnFailure, String parallelGroup) {
        this.stepId = stepId;
        this.toolName = toolName;
        this.purpose = purpose;
        this.inputSummary = inputSummary;
        this.continueOnFailure = continueOnFailure;
        this.parallelGroup = parallelGroup;
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

    public String getParallelGroup() {
        return parallelGroup;
    }

    public PlanStepStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStepStatus status) {
        this.status = status;
    }
}
