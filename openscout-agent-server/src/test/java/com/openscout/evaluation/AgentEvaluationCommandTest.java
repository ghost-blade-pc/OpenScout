package com.openscout.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationCommandTest {

    @Test
    void shouldGenerateDefaultEvaluationReport() {
        EvaluationTestSupport.Harness harness = EvaluationTestSupport.harness();
        List<EvaluationCase> cases = new EvaluationCaseLoader(harness.objectMapper()).loadDefaultCases();
        AgentEvaluationRunner runner = new AgentEvaluationRunner(
                harness.agentService(),
                harness.traceService(),
                harness.properties(),
                new EvaluationMetricCalculator()
        );
        EvaluationReport report = runner.run(cases);
        EvaluationReportWriter writer = new EvaluationReportWriter(
                harness.objectMapper(),
                Path.of("target", "openscout-evaluation")
        );

        EvaluationReportWriter.EvaluationReportFiles files = writer.write(report);

        assertThat(report.summary().allRequiredPassed()).isTrue();
        assertThat(report.summary().memoryHitCases()).isEqualTo(1);
        assertThat(report.summary().githubReadmeFetchCalls()).isZero();
        assertThat(report.summary().githubCallSavings()).isGreaterThanOrEqualTo(2);
        assertThat(files.json()).exists();
        assertThat(files.markdown()).exists();
    }
}
