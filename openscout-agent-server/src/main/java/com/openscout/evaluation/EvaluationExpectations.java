package com.openscout.evaluation;

import java.util.List;

public record EvaluationExpectations(
        List<String> expectedKeywords,
        List<String> expectedEvidence,
        List<String> expectedTraceEvents
) {

    public EvaluationExpectations {
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        expectedTraceEvents = expectedTraceEvents == null ? List.of() : List.copyOf(expectedTraceEvents);
    }

    public static EvaluationExpectations empty() {
        return new EvaluationExpectations(List.of(), List.of(), List.of());
    }
}
