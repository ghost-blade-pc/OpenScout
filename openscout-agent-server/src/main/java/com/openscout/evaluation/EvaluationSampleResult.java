package com.openscout.evaluation;

import com.openscout.agent.AgentAskResponse;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.runtime.AgentRuntimeMode;

import java.util.List;
import java.util.Map;

public record EvaluationSampleResult(
        String caseId,
        String question,
        AgentRuntimeMode mode,
        List<String> tags,
        boolean optional,
        boolean skipped,
        boolean passed,
        String traceId,
        String errorSummary,
        String answerPreview,
        List<EvaluationRecommendationSnapshot> topRecommendations,
        long latencyMs,
        EvaluationMetrics metrics,
        Map<String, Long> eventCounts,
        List<String> failureReasons
) {

    public EvaluationSampleResult {
        tags = tags == null ? List.of() : List.copyOf(tags);
        topRecommendations = topRecommendations == null ? List.of() : List.copyOf(topRecommendations);
        eventCounts = eventCounts == null ? Map.of() : Map.copyOf(eventCounts);
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
    }

    public static EvaluationSampleResult skipped(EvaluationCase evaluationCase) {
        return new EvaluationSampleResult(
                evaluationCase.id(),
                evaluationCase.question(),
                evaluationCase.mode(),
                evaluationCase.tags(),
                evaluationCase.optional(),
                true,
                false,
                null,
                "optional case skipped",
                null,
                List.of(),
                0,
                null,
                Map.of(),
                List.of()
        );
    }

    public static EvaluationSampleResult from(EvaluationCase evaluationCase,
                                              AgentAskResponse response,
                                              EvaluationMetrics metrics,
                                              Map<String, Long> eventCounts,
                                              String errorSummary) {
        List<ProjectRecommendation> recommendations = response == null || response.recommendations() == null
                ? List.of()
                : response.recommendations();
        List<EvaluationRecommendationSnapshot> snapshots = recommendations.stream()
                .limit(3)
                .map(EvaluationRecommendationSnapshot::from)
                .toList();
        String answer = response == null ? null : truncate(response.answer(), 300);
        String traceId = response == null ? null : response.traceId();
        long latencyMs = metrics == null ? 0 : metrics.latencyMs();
        List<String> failures = metrics == null ? List.of(errorSummary) : metrics.failureReasons();
        boolean passed = metrics == null || metrics.passed();
        return new EvaluationSampleResult(
                evaluationCase.id(),
                evaluationCase.question(),
                evaluationCase.mode(),
                evaluationCase.tags(),
                evaluationCase.optional(),
                false,
                passed,
                traceId,
                errorSummary,
                answer,
                snapshots,
                latencyMs,
                metrics,
                eventCounts,
                failures
        );
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...<truncated>";
    }
}
