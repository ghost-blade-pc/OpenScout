package com.openscout.agent.tool;

import com.openscout.config.OpenScoutProperties;
import com.openscout.learning.LearningPlanGenerator;
import com.openscout.learning.LearningPlanResponse;
import com.openscout.persistence.learning.LearningPlanPersistenceService;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class GenerateLearningPlanTool implements AgentTool {

    public static final String NAME = "generate_learning_plan";

    private static final Logger log = LoggerFactory.getLogger(GenerateLearningPlanTool.class);

    private final OpenScoutProperties properties;
    private final LearningPlanGenerator learningPlanGenerator;
    private final LearningPlanPersistenceService learningPlanPersistenceService;
    private final TraceService traceService;

    public GenerateLearningPlanTool(OpenScoutProperties properties,
                                    LearningPlanGenerator learningPlanGenerator,
                                    LearningPlanPersistenceService learningPlanPersistenceService,
                                    TraceService traceService) {
        this.properties = properties;
        this.learningPlanGenerator = learningPlanGenerator;
        this.learningPlanPersistenceService = learningPlanPersistenceService;
        this.traceService = traceService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        if (!properties.getLearning().isEnabled()) {
            request.context().setLearningPlan(null);
            return ToolResult.success("enabled=false skipped=true");
        }
        Instant generateStart = Instant.now();
        LearningPlanResponse plan = learningPlanGenerator.generate(
                request.context().getUserGoal(), request.context().getRecommendations());
        long generateLatencyMs = Duration.between(generateStart, Instant.now()).toMillis();
        traceService.recordToolCall(request.trace(), "learning_plan_generate",
                "goal=" + request.context().getUserGoal(),
                "tasks=" + plan.tasks().size(),
                generateLatencyMs);

        if (!properties.getPersistence().isEnabled() || plan.tasks().isEmpty()) {
            traceService.recordToolCall(request.trace(), "learning_plan_persist",
                    "enabled=" + properties.getPersistence().isEnabled(),
                    "persisted=false", 0);
            request.context().setLearningPlan(plan);
            return ToolResult.success("tasks=" + plan.tasks().size() + " persisted=false");
        }
        Instant persistStart = Instant.now();
        try {
            Long userId = request.trace() != null ? request.trace().getUserId() : null;
            LearningPlanResponse persisted = learningPlanPersistenceService.savePlan(plan, userId);
            traceService.recordToolCall(request.trace(), "learning_plan_persist",
                    "goalId=" + persisted.goalId(),
                    "persisted=true tasks=" + persisted.tasks().size(),
                    Duration.between(persistStart, Instant.now()).toMillis());
            request.context().setLearningPlan(persisted);
            return ToolResult.success("tasks=" + persisted.tasks().size() + " persisted=true");
        } catch (Exception e) {
            log.warn("learning plan 持久化失败，返回临时计划：{}", e.getMessage());
            traceService.recordToolCall(request.trace(), "learning_plan_persist",
                    "goal=" + request.context().getUserGoal(),
                    "persisted=false error=" + e.getMessage(),
                    Duration.between(persistStart, Instant.now()).toMillis());
            request.context().setLearningPlan(plan);
            return ToolResult.success("tasks=" + plan.tasks().size() + " persisted=false");
        }
    }
}
