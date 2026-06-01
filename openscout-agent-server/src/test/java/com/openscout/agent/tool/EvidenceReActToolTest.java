package com.openscout.agent.tool;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.react.EvidenceGapDetector;
import com.openscout.agent.react.ReadmeEnrichmentResult;
import com.openscout.agent.react.ReadmeEvidenceEnricher;
import com.openscout.agent.recommendation.RecommendationScoringService;
import com.openscout.agent.runtime.AgentContext;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.agent.runtime.PlanStep;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.persistence.analysis.RepoAnalysisPersistenceService;
import com.openscout.persistence.repo.RepoPersistenceService;
import com.openscout.scoring.ProjectScoreService;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import com.openscout.trace.TraceToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceReActToolTest {

    private OpenScoutProperties properties;
    private TraceService traceService;
    private ReadmeEvidenceEnricher enricher;
    private RecommendationScoringService recommendationScoringService;
    private EvidenceReActTool tool;

    @BeforeEach
    void setUp() {
        properties = new OpenScoutProperties();
        properties.getPersistence().setEnabled(false);
        traceService = new TraceService(properties, null);
        enricher = mock(ReadmeEvidenceEnricher.class);
        recommendationScoringService = new RecommendationScoringService(
                new ProjectScoreService(),
                properties,
                mock(RepoPersistenceService.class),
                mock(RepoAnalysisPersistenceService.class),
                traceService
        );
        tool = new EvidenceReActTool(
                properties,
                new EvidenceGapDetector(),
                enricher,
                recommendationScoringService,
                traceService
        );
    }

    @Test
    void shouldSkipWhenDisabled() {
        properties.getReact().setEnabled(false);
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, List.of(repo("example", "repo", 0, false)));

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("disabled");
        verify(enricher, never()).enrichFromGitHub(any());
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> assertThat(call.outputSummary()).contains("disabled"));
    }

    @Test
    void shouldSkipInMockMode() {
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.MOCK, List.of(repo("example", "repo", 0, false)));

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("mode_not_real");
        verify(enricher, never()).enrichFromGitHub(any());
    }

    @Test
    void shouldStopWhenNoRecommendations() {
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.REAL);

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("no_recommendations");
    }

    @Test
    void shouldStopWhenNoActionableGap() {
        RepoSummary repo = repo("example", "strong", 2500, true);
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, List.of(repo));

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("no_actionable_gap");
        verify(enricher, never()).enrichFromGitHub(any());
    }

    @Test
    void shouldFetchReadmeAndRescore() {
        RepoSummary original = repo("example", "repo", 0, false);
        RepoSummary enriched = repo("example", "repo", 2500, true);
        when(enricher.enrichFromGitHub(original))
                .thenReturn(ReadmeEnrichmentResult.fetched(enriched, 2500));
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, List.of(original));
        int beforeDocScore = context.getRecommendations().get(0).score().docScore();

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("no_actionable_gap");
        assertThat(context.getRepos().get(0).readmeLength()).isEqualTo(2500);
        assertThat(context.getRecommendations().get(0).score().docScore()).isGreaterThan(beforeDocScore);
        assertThat(trace.getToolCalls()).extracting(TraceToolCall::toolName)
                .contains("evidence_gap_detected", "evidence_follow_up_started",
                        "evidence_follow_up_observed", "evidence_rescore_completed",
                        "evidence_react_stopped");
    }

    @Test
    void shouldContinueOnPartialFailure() {
        RepoSummary missing = repo("example", "missing", 0, false);
        RepoSummary success = repo("example", "success", 0, false);
        RepoSummary enriched = repo("example", "success", 2500, true);
        when(enricher.enrichFromGitHub(missing))
                .thenReturn(ReadmeEnrichmentResult.skipped(missing, "not_found", "not found"));
        when(enricher.enrichFromGitHub(success))
                .thenReturn(ReadmeEnrichmentResult.fetched(enriched, 2500));
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, List.of(missing, success));

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("max_rounds_reached");
        assertThat(context.getRepos()).anySatisfy(repo -> {
            assertThat(repo.fullName()).isEqualTo("example/success");
            assertThat(repo.readmeLength()).isEqualTo(2500);
        });
    }

    @Test
    void shouldSkipRepoWhenReadmeAlreadyFailedInSameAsk() {
        RepoSummary missing = repo("example", "missing", 0, false);
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, List.of(missing));
        context.recordReadmeFailure("example/missing", "not_found");

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("max_rounds_reached");
        verify(enricher, never()).enrichFromGitHub(any());
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isEqualTo("evidence_follow_up_observed");
                    assertThat(call.outputSummary()).contains("skipped_previous_not_found");
                    assertThat(call.errorMessage()).contains("already failed earlier");
                });
    }

    @Test
    void shouldStopWhenReadmeAlreadyRateLimitedInSameAsk() {
        RepoSummary limited = repo("example", "limited", 0, false);
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, List.of(limited));
        context.recordReadmeFailure("example/limited", "rate_limited", 60);

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("rate_limited");
        verify(enricher, never()).enrichFromGitHub(any());
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isEqualTo("evidence_follow_up_observed");
                    assertThat(call.outputSummary()).contains("status=rate_limited", "retryAfterSeconds=60");
                    assertThat(call.errorMessage()).contains("already failed earlier");
                });
    }

    @Test
    void shouldStopFollowUpWhenRateLimited() {
        RepoSummary limited = repo("example", "limited", 0, false);
        RepoSummary skipped = repo("example", "skipped", 0, false);
        when(enricher.enrichFromGitHub(limited))
                .thenReturn(ReadmeEnrichmentResult.rateLimited(limited,
                        "retryAfterSeconds=60 api-key=secret", 60));
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, List.of(limited, skipped));

        ToolResult result = tool.execute(request(context, trace));

        assertThat(result.outputSummary()).contains("rate_limited");
        verify(enricher, times(1)).enrichFromGitHub(any());
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isEqualTo("evidence_follow_up_observed");
                    assertThat(call.errorMessage()).contains("api-key=<redacted>");
                });
    }

    @Test
    void shouldRespectMaxFollowUpRepos() {
        properties.getReact().setMaxFollowUpRepos(2);
        List<RepoSummary> repos = List.of(
                repo("example", "one", 0, false),
                repo("example", "two", 0, false),
                repo("example", "three", 0, false)
        );
        when(enricher.enrichFromGitHub(any()))
                .thenAnswer(invocation -> ReadmeEnrichmentResult.skipped(invocation.getArgument(0),
                        "not_found", "not found"));
        AgentTrace trace = traceService.start("learn Java");
        AgentContext context = context(AgentRuntimeMode.REAL, repos);

        tool.execute(request(context, trace));

        verify(enricher, times(2)).enrichFromGitHub(any());
    }

    private ToolRequest request(AgentContext context, AgentTrace trace) {
        return new ToolRequest(new PlanStep("step-5", "evidence_react", "purpose", "input", true),
                context, trace);
    }

    private AgentContext context(AgentRuntimeMode mode, List<RepoSummary> repos) {
        AgentContext context = new AgentContext("learn Java", mode);
        context.setRepos(repos);
        List<ProjectRecommendation> recommendations = recommendationScoringService.score("learn Java", repos);
        context.setRecommendations(recommendations);
        return context;
    }

    private RepoSummary repo(String owner, String name, int readmeLength, boolean hasExamples) {
        return new RepoSummary(
                owner,
                name,
                owner + "/" + name,
                "A Java learning project",
                "Java",
                1000,
                100,
                List.of("java", "spring"),
                "Apache-2.0",
                10,
                Instant.now(),
                Instant.now(),
                readmeLength,
                hasExamples,
                false,
                "github"
        );
    }
}
