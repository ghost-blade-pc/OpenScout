package com.openscout.evaluation;

import java.time.Instant;
import java.util.List;

public record EvaluationReport(
        Instant generatedAt,
        EvaluationEnvironment environment,
        EvaluationSummary summary,
        List<EvaluationSampleResult> samples
) {

    public EvaluationReport {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    public static EvaluationReport from(EvaluationEnvironment environment, List<EvaluationSampleResult> samples) {
        return new EvaluationReport(Instant.now(), environment, EvaluationSummary.from(samples), samples);
    }
}
