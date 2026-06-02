package com.openscout.evaluation;

import com.openscout.agent.AgentAskResponse;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.scoring.ProjectScore;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceToolCall;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationMetricCalculatorTest {

    private final EvaluationMetricCalculator calculator = new EvaluationMetricCalculator();

    @Test
    void shouldCalculateMetricsFromResponseAndTrace() {
        EvaluationCase evaluationCase = new EvaluationCase(
                "case-1",
                "learn Spring AI",
                AgentRuntimeMode.MOCK,
                false,
                List.of("mock"),
                new EvaluationExpectations(
                        List.of("spring", "ai"),
                        List.of("README", "updated"),
                        List.of("repo_search_mock", "verify_completed")),
                new EvaluationThresholds(1.0, 1.0, 1000)
        );
        AgentAskResponse response = new AgentAskResponse(
                "trace-1",
                "已基于规则评分完成项目推荐。（LLM 不可用，返回模板回答。）",
                List.of(recommendation()),
                null,
                10
        );
        AgentTrace trace = trace("trace-1");
        trace.getToolCalls().add(call("repo_search_mock", "keyword=spring", "items=1", "SUCCESS"));
        trace.getToolCalls().add(call("verify_completed", "scoreIntegrity=ok", "allOk=true", "SUCCESS"));

        EvaluationMetrics metrics = calculator.calculate(evaluationCase, response, trace, null);

        assertThat(metrics.passed()).isTrue();
        assertThat(metrics.recommendationRelevance()).isEqualTo(1.0);
        assertThat(metrics.evidenceCoverage()).isEqualTo(1.0);
        assertThat(metrics.fallbackObserved()).isTrue();
        assertThat(metrics.verifierPassed()).isTrue();
    }

    @Test
    void shouldReportMemoryHitAndVerifierIssues() {
        EvaluationCase evaluationCase = new EvaluationCase(
                "case-2",
                "learn Go",
                AgentRuntimeMode.REAL,
                false,
                List.of(),
                new EvaluationExpectations(List.of("go"), List.of(), List.of("memory_hit")),
                EvaluationThresholds.defaults()
        );
        AgentAskResponse response = new AgentAskResponse(
                "trace-2",
                "answer",
                List.of(recommendation()),
                null,
                5
        );
        AgentTrace trace = trace("trace-2");
        trace.getToolCalls().add(call("memory_hit", "keyword=go", "fresh=1", "SUCCESS"));
        trace.getToolCalls().add(call("search_repos_skipped", "source=memory", "repos=1", "SUCCESS"));
        trace.getToolCalls().add(call("readme_cache_hit", "repo=a/b", "freshness=now", "SUCCESS"));
        trace.getToolCalls().add(call("readme_fetch_github", "targets=1", "fetched=0 skipped=0 cacheHit=1", "SUCCESS"));
        trace.getToolCalls().add(call("verify_completed", "evidenceClaims=issues", "totalIssues=2", "SUCCESS"));

        EvaluationMetrics metrics = calculator.calculate(evaluationCase, response, trace, null);

        assertThat(metrics.memoryHit()).isTrue();
        assertThat(metrics.githubCallSavings()).isEqualTo(2);
        assertThat(metrics.githubReadmeFetchCalls()).isZero();
        assertThat(metrics.verifierIssueCount()).isEqualTo(2);
        assertThat(metrics.verifierPassed()).isFalse();
    }

    @Test
    void shouldNotTreatRequiredSkippedSampleAsPassed() {
        EvaluationCase requiredCase = new EvaluationCase(
                "required-case",
                "learn React",
                AgentRuntimeMode.REAL,
                false,
                List.of(),
                new EvaluationExpectations(List.of("react"), List.of(), List.of()),
                EvaluationThresholds.defaults()
        );

        EvaluationSummary summary = EvaluationSummary.from(List.of(EvaluationSampleResult.skipped(requiredCase)));

        assertThat(summary.allRequiredPassed()).isFalse();
    }

    private ProjectRecommendation recommendation() {
        return new ProjectRecommendation(
                "spring-projects/spring-ai",
                "Spring AI Agent project",
                "Java",
                12_000,
                Instant.now().toString(),
                new ProjectScore(90, 20, 20, 25, 15, 10,
                        List.of("activity: updated within 180 days", "docs: README length >= 2000")),
                "Spring AI matched"
        );
    }

    private AgentTrace trace(String traceId) {
        return new AgentTrace(traceId, "question", Instant.now());
    }

    private TraceToolCall call(String toolName, String input, String output, String status) {
        return new TraceToolCall(toolName, input, output, 1, status, null, Instant.now());
    }
}
