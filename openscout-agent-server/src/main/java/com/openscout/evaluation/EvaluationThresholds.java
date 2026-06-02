package com.openscout.evaluation;

public record EvaluationThresholds(
        double minRecommendationRelevance,
        double minEvidenceCoverage,
        long maxLatencyMs
) {

    public static EvaluationThresholds defaults() {
        return new EvaluationThresholds(0.5, 0.5, 10_000);
    }
}
