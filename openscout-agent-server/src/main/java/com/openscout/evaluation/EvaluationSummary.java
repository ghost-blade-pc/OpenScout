package com.openscout.evaluation;

import java.util.List;

public record EvaluationSummary(
        int totalCases,
        int passedCases,
        int failedCases,
        int skippedCases,
        boolean allRequiredPassed,
        double averageRecommendationRelevance,
        double averageEvidenceCoverage,
        long maxLatencyMs,
        long p50LatencyMs,
        long p95LatencyMs,
        int memoryHitCases,
        int fallbackCases,
        int verifierIssueCases,
        long githubSearchCalls,
        long githubReadmeFetchCalls,
        long githubCallSavings
) {

    public static EvaluationSummary from(List<EvaluationSampleResult> samples) {
        List<EvaluationSampleResult> executed = samples.stream()
                .filter(sample -> !sample.skipped() && sample.metrics() != null)
                .toList();
        int skipped = (int) samples.stream().filter(EvaluationSampleResult::skipped).count();
        int passed = (int) samples.stream().filter(sample -> !sample.skipped() && sample.passed()).count();
        int failed = (int) samples.stream().filter(sample -> !sample.skipped() && !sample.passed()).count();
        boolean allRequiredPassed = samples.stream()
                .filter(sample -> !sample.optional())
                .allMatch(sample -> !sample.skipped() && sample.passed());
        List<Long> latencies = executed.stream()
                .map(EvaluationSampleResult::latencyMs)
                .sorted()
                .toList();
        return new EvaluationSummary(
                samples.size(),
                passed,
                failed,
                skipped,
                allRequiredPassed,
                average(executed.stream().map(sample -> sample.metrics().recommendationRelevance()).toList()),
                average(executed.stream().map(sample -> sample.metrics().evidenceCoverage()).toList()),
                latencies.stream().mapToLong(Long::longValue).max().orElse(0),
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                (int) executed.stream().filter(sample -> sample.metrics().memoryHit()).count(),
                (int) executed.stream().filter(sample -> sample.metrics().fallbackObserved()).count(),
                (int) executed.stream().filter(sample -> sample.metrics().verifierIssueCount() > 0).count(),
                executed.stream().mapToLong(sample -> sample.metrics().githubSearchCalls()).sum(),
                executed.stream().mapToLong(sample -> sample.metrics().githubReadmeFetchCalls()).sum(),
                executed.stream().mapToLong(sample -> sample.metrics().githubCallSavings()).sum()
        );
    }

    private static double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
