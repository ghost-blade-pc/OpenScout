package com.openscout.agent.runtime;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.tool.ToolExecutor;
import com.openscout.agent.tool.ToolRequest;
import com.openscout.agent.tool.ToolResult;
import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlanExecutor {

    private final RuleBasedAgentPlanner planner;
    private final TraceService traceService;
    private final OpenScoutProperties properties;
    private final ToolExecutor toolExecutor;

    public PlanExecutor(RuleBasedAgentPlanner planner,
                        TraceService traceService,
                        OpenScoutProperties properties,
                        ToolExecutor toolExecutor) {
        this.planner = planner;
        this.traceService = traceService;
        this.properties = properties;
        this.toolExecutor = toolExecutor;
    }

    public AgentRuntimeResult execute(String question, AgentTrace trace) {
        AgentPlan plan = planner.plan(question, properties.isMockAgent());
        traceService.recordPlanCreated(trace, plan);
        AgentContext context = new AgentContext(question, plan.getMode());
        for (PlanStep step : plan.getSteps()) {
            executeStep(step, context, trace);
        }
        return new AgentRuntimeResult(
                context.getAnswer(),
                context.getRecommendations(),
                context.getLearningPlan(),
                buildScoreSummary(context.getRecommendations())
        );
    }

    private void executeStep(PlanStep step, AgentContext context, AgentTrace trace) {
        step.setStatus(PlanStepStatus.RUNNING);
        traceService.recordStepStarted(trace, step);
        ToolResult result;
        try {
            result = toolExecutor.execute(new ToolRequest(step, context, trace));
        } catch (RuntimeException ex) {
            step.setStatus(PlanStepStatus.FAILED);
            recordFinished(trace, step, ToolResult.recoverableFailure(null, ex.getMessage()));
            if (!step.isContinueOnFailure()) {
                throw ex;
            }
            return;
        }
        step.setStatus(result.status());
        recordFinished(trace, step, result);
        if (result.status() == PlanStepStatus.FAILED && !step.isContinueOnFailure()) {
            throw new IllegalStateException(result.errorSummary());
        }
    }

    private void recordFinished(AgentTrace trace, PlanStep step, ToolResult result) {
        StepObservation observation = new StepObservation(
                step.getStepId(),
                step.getToolName(),
                step.getStatus(),
                result.outputSummary(),
                result.errorSummary(),
                result.latencyMs()
        );
        traceService.recordStepFinished(trace, step, observation);
        traceService.recordObservation(trace, observation);
    }

    private String buildScoreSummary(List<ProjectRecommendation> recommendations) {
        return recommendations.stream()
                .map(item -> item.fullName() + "=" + item.score().totalScore())
                .reduce((left, right) -> left + ", " + right)
                .orElse("no recommendations");
    }
}
