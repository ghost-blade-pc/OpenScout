package com.openscout.agent;

import com.openscout.learning.LearningPlanResponse;

import java.util.List;

public record AgentAskResponse(
        String traceId,
        String answer,
        List<ProjectRecommendation> recommendations,
        LearningPlanResponse learningPlan,
        long latencyMs
) {
}
