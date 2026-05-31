package com.openscout.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedAgentPlannerTest {

    private final RuleBasedAgentPlanner planner = new RuleBasedAgentPlanner();

    @Test
    void shouldCreateStableMockPlan() {
        AgentPlan plan = planner.plan("learn Spring AI", true);

        assertThat(plan.getMode()).isEqualTo(AgentRuntimeMode.MOCK);
        assertThat(plan.getSteps()).extracting(PlanStep::getToolName)
                .containsExactly(
                        "interpret_goal",
                        "check_memory",
                        "search_repos",
                        "score_projects",
                        "generate_learning_plan",
                        "generate_answer"
                );
        assertThat(plan.getSteps()).allMatch(step -> step.getStatus() == PlanStepStatus.PENDING);
        assertThat(plan.getSteps())
                .filteredOn(step -> "check_memory".equals(step.getToolName()))
                .singleElement()
                .matches(PlanStep::isContinueOnFailure);
    }

    @Test
    void shouldCreateRealPlanWithReadmeStep() {
        AgentPlan plan = planner.plan("learn Go microservices", false);

        assertThat(plan.getMode()).isEqualTo(AgentRuntimeMode.REAL);
        assertThat(plan.getSteps()).extracting(PlanStep::getToolName)
                .containsExactly(
                        "interpret_goal",
                        "check_memory",
                        "search_repos",
                        "fetch_readme",
                        "score_projects",
                        "generate_learning_plan",
                        "generate_answer"
                );
        assertThat(plan.getSteps())
                .filteredOn(step -> "fetch_readme".equals(step.getToolName()))
                .singleElement()
                .matches(PlanStep::isContinueOnFailure);
    }
}
