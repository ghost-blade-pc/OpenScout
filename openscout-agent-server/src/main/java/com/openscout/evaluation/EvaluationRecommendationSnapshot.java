package com.openscout.evaluation;

import com.openscout.agent.ProjectRecommendation;

public record EvaluationRecommendationSnapshot(
        String fullName,
        String language,
        int score,
        int evidenceCount
) {

    public static EvaluationRecommendationSnapshot from(ProjectRecommendation recommendation) {
        int evidenceCount = recommendation.score() == null || recommendation.score().evidence() == null
                ? 0
                : recommendation.score().evidence().size();
        int score = recommendation.score() == null ? 0 : recommendation.score().totalScore();
        return new EvaluationRecommendationSnapshot(
                recommendation.fullName(),
                recommendation.language(),
                score,
                evidenceCount
        );
    }
}
