package com.openscout.evaluation;

import com.openscout.agent.runtime.AgentRuntimeMode;

import java.util.List;

public record EvaluationCase(
        String id,
        String question,
        AgentRuntimeMode mode,
        boolean optional,
        List<String> tags,
        EvaluationExpectations expectations,
        EvaluationThresholds thresholds
) {

    public EvaluationCase {
        tags = tags == null ? List.of() : List.copyOf(tags);
        expectations = expectations == null ? EvaluationExpectations.empty() : expectations;
        thresholds = thresholds == null ? EvaluationThresholds.defaults() : thresholds;
    }
}
