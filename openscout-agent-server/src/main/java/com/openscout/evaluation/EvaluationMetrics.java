package com.openscout.evaluation;

import java.util.List;

public record EvaluationMetrics(
        double recommendationRelevance,
        double evidenceCoverage,
        boolean verifierPassed,
        int verifierIssueCount,
        boolean memoryHit,
        long memoryHitEvents,
        long searchSkippedEvents,
        long readmeCacheHitEvents,
        boolean fallbackObserved,
        long latencyMs,
        long githubSearchCalls,
        long githubReadmeFetchCalls,
        long githubCallSavings,
        List<String> failureReasons
) {

    public EvaluationMetrics {
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
    }

    public boolean passed() {
        return failureReasons.isEmpty();
    }
}
