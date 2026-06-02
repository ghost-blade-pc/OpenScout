package com.openscout.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class EvaluationCaseLoader {

    private static final String DEFAULT_RESOURCE = "evaluation/cases.json";

    private final ObjectMapper objectMapper;

    public EvaluationCaseLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvaluationCase> loadDefaultCases() {
        return loadFromClasspath(DEFAULT_RESOURCE);
    }

    public List<EvaluationCase> loadFromClasspath(String resourcePath) {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Evaluation cases resource not found: " + resourcePath);
            }
            EvaluationCaseSet caseSet = objectMapper.readValue(input, EvaluationCaseSet.class);
            validate(caseSet.cases());
            return caseSet.cases();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to load evaluation cases from " + resourcePath, ex);
        }
    }

    public void validate(List<EvaluationCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Evaluation cases must not be empty");
        }
        for (EvaluationCase evaluationCase : cases) {
            validateCase(evaluationCase);
        }
    }

    private void validateCase(EvaluationCase evaluationCase) {
        if (evaluationCase == null) {
            throw new IllegalArgumentException("Evaluation case must not be null");
        }
        if (isBlank(evaluationCase.id())) {
            throw new IllegalArgumentException("Evaluation case id must not be blank");
        }
        if (isBlank(evaluationCase.question())) {
            throw new IllegalArgumentException("Evaluation case question must not be blank: " + evaluationCase.id());
        }
        if (evaluationCase.mode() == null) {
            throw new IllegalArgumentException("Evaluation case mode must not be null: " + evaluationCase.id());
        }
        EvaluationExpectations expectations = evaluationCase.expectations();
        boolean hasExpectation = !expectations.expectedKeywords().isEmpty()
                || !expectations.expectedEvidence().isEmpty()
                || !expectations.expectedTraceEvents().isEmpty();
        if (!hasExpectation) {
            throw new IllegalArgumentException("Evaluation case must have at least one expectation: " + evaluationCase.id());
        }
        EvaluationThresholds thresholds = evaluationCase.thresholds();
        if (thresholds.minRecommendationRelevance() < 0 || thresholds.minRecommendationRelevance() > 1) {
            throw new IllegalArgumentException("minRecommendationRelevance must be between 0 and 1: " + evaluationCase.id());
        }
        if (thresholds.minEvidenceCoverage() < 0 || thresholds.minEvidenceCoverage() > 1) {
            throw new IllegalArgumentException("minEvidenceCoverage must be between 0 and 1: " + evaluationCase.id());
        }
        if (thresholds.maxLatencyMs() <= 0) {
            throw new IllegalArgumentException("maxLatencyMs must be positive: " + evaluationCase.id());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
