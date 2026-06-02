package com.openscout.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationRunnerTest {

    @Test
    void shouldRunDefaultMockCaseAndCollectTraceEvidence() {
        EvaluationTestSupport.Harness harness = EvaluationTestSupport.harness();
        List<EvaluationCase> cases = new EvaluationCaseLoader(harness.objectMapper()).loadDefaultCases();
        AgentEvaluationRunner runner = new AgentEvaluationRunner(
                harness.agentService(),
                harness.traceService(),
                harness.properties(),
                new EvaluationMetricCalculator()
        );

        EvaluationReport report = runner.run(cases);

        assertThat(report.summary().allRequiredPassed()).isTrue();
        assertThat(report.samples()).anySatisfy(sample -> {
            assertThat(sample.caseId()).isEqualTo("mock-spring-ai-agent");
            assertThat(sample.mode()).isEqualTo(com.openscout.agent.runtime.AgentRuntimeMode.MOCK);
            assertThat(sample.passed()).isTrue();
            assertThat(sample.traceId()).isNotBlank();
            assertThat(sample.eventCounts()).containsKeys("repo_search_mock", "verify_completed");
            assertThat(sample.topRecommendations()).extracting(EvaluationRecommendationSnapshot::fullName)
                    .contains("spring-projects/spring-ai");
        });
        assertThat(report.samples()).anySatisfy(sample -> {
            assertThat(sample.caseId()).isEqualTo("real-memory-hit-react");
            assertThat(sample.mode()).isEqualTo(com.openscout.agent.runtime.AgentRuntimeMode.REAL);
            assertThat(sample.passed()).isTrue();
            assertThat(sample.metrics().memoryHit()).isTrue();
            assertThat(sample.metrics().githubReadmeFetchCalls()).isZero();
            assertThat(sample.metrics().githubCallSavings()).isGreaterThanOrEqualTo(2);
            assertThat(sample.eventCounts()).containsKeys("memory_hit", "search_repos_skipped", "readme_cache_hit");
            assertThat(sample.topRecommendations()).extracting(EvaluationRecommendationSnapshot::fullName)
                    .contains("facebook/react");
        });
        assertThat(report.samples()).anySatisfy(sample -> {
            assertThat(sample.caseId()).isEqualTo("optional-real-github-memory");
            assertThat(sample.mode()).isEqualTo(com.openscout.agent.runtime.AgentRuntimeMode.REAL);
            assertThat(sample.skipped()).isTrue();
            assertThat(sample.passed()).isFalse();
        });
        assertThat(report.summary().memoryHitCases()).isEqualTo(1);
        assertThat(report.summary().githubReadmeFetchCalls()).isZero();
        assertThat(report.summary().githubCallSavings()).isGreaterThanOrEqualTo(2);
    }
}
