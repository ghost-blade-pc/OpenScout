package com.openscout.agent.runtime;

import java.util.List;

public class AgentPlan {

    private final String planId;
    private final AgentRuntimeMode mode;
    private final List<PlanStep> steps;

    public AgentPlan(String planId, AgentRuntimeMode mode, List<PlanStep> steps) {
        this.planId = planId;
        this.mode = mode;
        this.steps = List.copyOf(steps);
    }

    public String getPlanId() {
        return planId;
    }

    public AgentRuntimeMode getMode() {
        return mode;
    }

    public List<PlanStep> getSteps() {
        return steps;
    }
}
