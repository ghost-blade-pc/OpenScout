package com.openscout.evaluation;

import com.openscout.agent.AgentAskResponse;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EvaluationMetricCalculator {

    private static final Pattern TOTAL_ISSUES_PATTERN = Pattern.compile("totalIssues=(\\d+)");
    private static final Pattern FETCHED_PATTERN = Pattern.compile("fetched=(\\d+)");

    public EvaluationMetrics calculate(EvaluationCase evaluationCase,
                                       AgentAskResponse response,
                                       AgentTrace trace,
                                       String errorSummary) {
        Map<String, Long> eventCounts = eventCounts(trace);
        long latencyMs = response != null ? response.latencyMs() : trace == null ? 0 : trace.getLatencyMs();
        double recommendationRelevance = recommendationRelevance(evaluationCase, response);
        double evidenceCoverage = evidenceCoverage(evaluationCase, response);
        int verifierIssueCount = verifierIssueCount(trace);
        boolean verifierPassed = verifierIssueCount == 0;
        boolean memoryHit = eventCounts.getOrDefault("memory_hit", 0L) > 0
                || eventCounts.getOrDefault("search_repos_skipped", 0L) > 0;
        boolean fallbackObserved = fallbackObserved(response, trace, errorSummary);
        long githubSearchCalls = eventCounts.getOrDefault("repo_search_github", 0L);
        long githubReadmeFetchCalls = githubReadmeFetchCalls(trace);
        long githubCallSavings = eventCounts.getOrDefault("search_repos_skipped", 0L)
                + eventCounts.getOrDefault("readme_cache_hit", 0L);

        List<String> failures = new ArrayList<>();
        EvaluationThresholds thresholds = evaluationCase.thresholds();
        if (recommendationRelevance < thresholds.minRecommendationRelevance()) {
            failures.add("recommendation relevance " + recommendationRelevance
                    + " below " + thresholds.minRecommendationRelevance());
        }
        if (evidenceCoverage < thresholds.minEvidenceCoverage()) {
            failures.add("evidence coverage " + evidenceCoverage
                    + " below " + thresholds.minEvidenceCoverage());
        }
        if (latencyMs > thresholds.maxLatencyMs()) {
            failures.add("latency " + latencyMs + "ms above " + thresholds.maxLatencyMs() + "ms");
        }
        for (String expectedEvent : evaluationCase.expectations().expectedTraceEvents()) {
            if (eventCounts.getOrDefault(expectedEvent, 0L) == 0) {
                failures.add("missing trace event " + expectedEvent);
            }
        }
        if (errorSummary != null && !errorSummary.isBlank()) {
            failures.add("agent error: " + errorSummary);
        }

        return new EvaluationMetrics(
                recommendationRelevance,
                evidenceCoverage,
                verifierPassed,
                verifierIssueCount,
                memoryHit,
                eventCounts.getOrDefault("memory_hit", 0L),
                eventCounts.getOrDefault("search_repos_skipped", 0L),
                eventCounts.getOrDefault("readme_cache_hit", 0L),
                fallbackObserved,
                latencyMs,
                githubSearchCalls,
                githubReadmeFetchCalls,
                githubCallSavings,
                failures
        );
    }

    public Map<String, Long> eventCounts(AgentTrace trace) {
        if (trace == null) {
            return Map.of();
        }
        return trace.getToolCalls().stream()
                .collect(Collectors.groupingBy(TraceToolCall::toolName, LinkedHashMap::new, Collectors.counting()));
    }

    private long githubReadmeFetchCalls(AgentTrace trace) {
        if (trace == null) {
            return 0;
        }
        return trace.getToolCalls().stream()
                .filter(call -> "readme_fetch_github".equals(call.toolName()))
                .mapToLong(this::githubReadmeFetchCalls)
                .sum();
    }

    private long githubReadmeFetchCalls(TraceToolCall call) {
        Matcher matcher = FETCHED_PATTERN.matcher(nullToEmpty(call.outputSummary()));
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 1;
    }

    private double recommendationRelevance(EvaluationCase evaluationCase, AgentAskResponse response) {
        List<String> expected = evaluationCase.expectations().expectedKeywords();
        if (expected.isEmpty()) {
            return 1.0;
        }
        String haystack = recommendationText(response);
        long matches = expected.stream()
                .filter(keyword -> containsIgnoreCase(haystack, keyword))
                .count();
        return ratio(matches, expected.size());
    }

    private String recommendationText(AgentAskResponse response) {
        if (response == null || response.recommendations() == null) {
            return "";
        }
        return response.recommendations().stream()
                .map(this::recommendationText)
                .collect(Collectors.joining("\n"));
    }

    private String recommendationText(ProjectRecommendation recommendation) {
        String evidence = recommendation.score() == null || recommendation.score().evidence() == null
                ? ""
                : String.join(" ", recommendation.score().evidence());
        return String.join(" ",
                nullToEmpty(recommendation.fullName()),
                nullToEmpty(recommendation.description()),
                nullToEmpty(recommendation.language()),
                nullToEmpty(recommendation.reason()),
                evidence
        );
    }

    private double evidenceCoverage(EvaluationCase evaluationCase, AgentAskResponse response) {
        List<String> expected = evaluationCase.expectations().expectedEvidence();
        if (expected.isEmpty()) {
            return hasAnyEvidence(response) ? 1.0 : 0.0;
        }
        String haystack = evidenceText(response);
        long matches = expected.stream()
                .filter(keyword -> containsIgnoreCase(haystack, keyword))
                .count();
        return ratio(matches, expected.size());
    }

    private boolean hasAnyEvidence(AgentAskResponse response) {
        return response != null && response.recommendations() != null
                && response.recommendations().stream()
                .anyMatch(rec -> rec.score() != null && rec.score().evidence() != null
                        && !rec.score().evidence().isEmpty());
    }

    private String evidenceText(AgentAskResponse response) {
        if (response == null || response.recommendations() == null) {
            return "";
        }
        return response.recommendations().stream()
                .filter(rec -> rec.score() != null && rec.score().evidence() != null)
                .flatMap(rec -> rec.score().evidence().stream())
                .collect(Collectors.joining(" "));
    }

    private int verifierIssueCount(AgentTrace trace) {
        if (trace == null) {
            return 0;
        }
        return trace.getToolCalls().stream()
                .filter(call -> "verify_completed".equals(call.toolName()))
                .mapToInt(this::verifierIssueCount)
                .sum();
    }

    private int verifierIssueCount(TraceToolCall call) {
        String output = nullToEmpty(call.outputSummary());
        Matcher matcher = TOTAL_ISSUES_PATTERN.matcher(output);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (output.contains("issues") || nullToEmpty(call.inputSummary()).contains("issues")) {
            return 1;
        }
        return 0;
    }

    private boolean fallbackObserved(AgentAskResponse response, AgentTrace trace, String errorSummary) {
        if (errorSummary != null && !errorSummary.isBlank()) {
            return true;
        }
        if (response != null && containsIgnoreCase(response.answer(), "LLM 不可用")) {
            return true;
        }
        if (trace == null) {
            return false;
        }
        return trace.getToolCalls().stream().anyMatch(call ->
                containsIgnoreCase(call.outputSummary(), "fallback")
                        || containsIgnoreCase(call.outputSummary(), "不可用")
                        || containsIgnoreCase(call.errorMessage(), "fallback"));
    }

    private double ratio(long numerator, int denominator) {
        if (denominator <= 0) {
            return 1.0;
        }
        return (double) numerator / denominator;
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        if (value == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
