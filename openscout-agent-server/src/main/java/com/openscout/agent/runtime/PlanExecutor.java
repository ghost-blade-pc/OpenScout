package com.openscout.agent.runtime;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.tool.ToolExecutor;
import com.openscout.agent.tool.ToolRequest;
import com.openscout.agent.tool.ToolResult;
import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final RuleBasedAgentPlanner planner;
    private final TraceService traceService;
    private final OpenScoutProperties properties;
    private final ToolExecutor toolExecutor;
    private final ExecutorService parallelExecutor;

    public PlanExecutor(RuleBasedAgentPlanner planner,
                        TraceService traceService,
                        OpenScoutProperties properties,
                        ToolExecutor toolExecutor) {
        this.planner = planner;
        this.traceService = traceService;
        this.properties = properties;
        this.toolExecutor = toolExecutor;
        this.parallelExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "plan-parallel");
            t.setDaemon(true);
            return t;
        });
    }

    public AgentRuntimeResult execute(String question, AgentTrace trace) {
        AgentPlan plan = planner.plan(question, properties.isMockAgent());
        traceService.recordPlanCreated(trace, plan);
        AgentContext context = new AgentContext(question, plan.getMode());
        List<PlanStep> steps = plan.getSteps();
        int i = 0;
        while (i < steps.size()) {
            PlanStep step = steps.get(i);
            if (step.getParallelGroup() != null) {
                // 收集同组连续步骤并并发执行
                List<PlanStep> group = new ArrayList<>();
                String groupId = step.getParallelGroup();
                while (i < steps.size() && groupId.equals(steps.get(i).getParallelGroup())) {
                    group.add(steps.get(i));
                    i++;
                }
                executeParallelGroup(group, context, trace);
            } else {
                executeStep(step, context, trace);
                i++;
            }
        }
        return new AgentRuntimeResult(
                context.getAnswer(),
                context.getRecommendations(),
                context.getLearningPlan(),
                buildScoreSummary(context.getRecommendations())
        );
    }

    /**
     * 并发执行同一 parallelGroup 内的所有步骤，等待全部完成后继续。
     * 任一步骤失败且非 continueOnFailure 时，取消其余任务并向上抛异常。
     */
    private void executeParallelGroup(List<PlanStep> group, AgentContext context, AgentTrace trace) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();

        for (PlanStep step : group) {
            futures.add(CompletableFuture.runAsync(() -> {
                executeStep(step, context, trace);
            }, parallelExecutor).exceptionally(ex -> {
                if (ex.getCause() instanceof RuntimeException re) {
                    synchronized (failures) {
                        failures.add(re);
                    }
                } else {
                    synchronized (failures) {
                        failures.add(new RuntimeException(ex));
                    }
                }
                return null;
            }));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(properties.getLlm().getTimeoutSeconds() * 2L, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("parallel group execution interrupted or timed out: {}", e.getMessage());
            failures.add(new RuntimeException("Parallel group execution failed: " + e.getMessage()));
        }

        // 检查是否有 fatal 失败（非 continueOnFailure 的步骤失败）
        for (PlanStep step : group) {
            if (step.getStatus() == PlanStepStatus.FAILED && !step.isContinueOnFailure()) {
                RuntimeException fatal = failures.isEmpty() ? null : failures.get(0);
                if (fatal != null) {
                    throw fatal;
                }
                throw new IllegalStateException("Parallel step " + step.getStepId() + " failed");
            }
        }
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
