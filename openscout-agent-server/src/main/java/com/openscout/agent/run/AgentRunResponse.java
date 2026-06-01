package com.openscout.agent.run;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.learning.LearningPlanResponse;

import java.time.Instant;
import java.util.List;

public record AgentRunResponse(
        String runId,
        String traceId,
        AgentRunStatus status,
        String answer,
        List<ProjectRecommendation> recommendations,
        LearningPlanResponse learningPlan,
        String errorSummary,
        long latencyMs,
        Instant createdAt,
        Instant completedAt,
        String eventsUrl
) {
}
