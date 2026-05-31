package com.openscout.agent.runtime;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.learning.LearningPlanResponse;

import java.util.List;

public record AgentRuntimeResult(
        String answer,
        List<ProjectRecommendation> recommendations,
        LearningPlanResponse learningPlan,
        String scoreSummary
) {
}
