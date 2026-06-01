package com.openscout.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.agent.tool.CheckMemoryTool;
import com.openscout.agent.tool.EvidenceReActTool;
import com.openscout.agent.tool.FetchReadmeTool;
import com.openscout.agent.tool.GenerateAnswerTool;
import com.openscout.agent.tool.GenerateLearningPlanTool;
import com.openscout.agent.tool.InterpretGoalTool;
import com.openscout.agent.tool.ScoreProjectsTool;
import com.openscout.agent.tool.SearchReposTool;
import com.openscout.agent.tool.ToolExecutor;
import com.openscout.agent.tool.ToolRegistry;
import com.openscout.agent.tool.VerifyAnswerTool;
import com.openscout.agent.react.EvidenceGapDetector;
import com.openscout.agent.react.ReadmeEvidenceEnricher;
import com.openscout.agent.recommendation.RecommendationScoringService;
import com.openscout.client.CollectorClient;
import com.openscout.client.CollectorUnavailableException;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.learning.LearningPlanGenerator;
import com.openscout.memory.ProjectMemoryService;
import com.openscout.persistence.analysis.RepoAnalysisPersistenceService;
import com.openscout.persistence.learning.LearningPlanPersistenceService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanExecutorTest {

    private OpenScoutProperties properties;
    private CollectorClient collectorClient;
    private TraceService traceService;
    private PlanExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new OpenScoutProperties();
        properties.getLlm().setEnabled(false);
        properties.getPersistence().setEnabled(false);
        properties.getMemory().setEnabled(false); // 默认关闭 memory，避免干扰既有测试
        collectorClient = mock(CollectorClient.class);
        traceService = new TraceService(properties, null);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectMemoryService memoryService = new ProjectMemoryService(
                null, null, properties, objectMapper);
        ReadmeEvidenceEnricher readmeEvidenceEnricher = new ReadmeEvidenceEnricher(collectorClient);
        RecommendationScoringService recommendationScoringService = new RecommendationScoringService(
                new ProjectScoreService(),
                properties,
                mock(RepoPersistenceService.class),
                mock(RepoAnalysisPersistenceService.class),
                traceService
        );
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new InterpretGoalTool(new com.openscout.agent.GoalInterpreter(properties, objectMapper), traceService),
                new CheckMemoryTool(memoryService, traceService),
                new SearchReposTool(collectorClient, traceService),
                new FetchReadmeTool(traceService, memoryService, readmeEvidenceEnricher),
                new ScoreProjectsTool(recommendationScoringService),
                new EvidenceReActTool(properties, new EvidenceGapDetector(), readmeEvidenceEnricher,
                        recommendationScoringService, traceService),
                new GenerateLearningPlanTool(properties,
                        new LearningPlanGenerator(properties, objectMapper),
                        mock(LearningPlanPersistenceService.class),
                        traceService),
                new GenerateAnswerTool(new com.openscout.agent.AnswerGenerator(properties), traceService),
                new VerifyAnswerTool(properties, traceService)
        ));
        executor = new PlanExecutor(
                new RuleBasedAgentPlanner(),
                traceService,
                properties,
                new ToolExecutor(toolRegistry, traceService)
        );
    }

    @Test
    void shouldExecuteMockPlanAndRecordRuntimeEvents() {
        when(collectorClient.fetchMockRepos(anyString())).thenReturn(List.of(repo("spring-projects", "spring-ai", 2500)));
        AgentTrace trace = traceService.start("learn Spring AI");

        AgentRuntimeResult result = executor.execute("learn Spring AI", trace);

        assertThat(result.recommendations()).hasSize(1);
        assertThat(result.learningPlan()).isNotNull();
        assertThat(result.learningPlan().tasks()).hasSize(7);
        assertThat(result.answer()).contains("已基于规则评分完成项目推荐");
        assertThat(trace.getToolCalls()).extracting(TraceToolCall::toolName)
                .contains("agent_plan_created", "agent_step_started", "agent_step_finished",
                        "agent_observation_created", "agent_tool_started", "agent_tool_finished",
                        "repo_search_mock", "learning_plan_generate", "memory_check",
                        "evidence_react_stopped", "verify_completed");
    }

    @Test
    void shouldRecordFailedStepWhenSearchFails() {
        when(collectorClient.fetchMockRepos(anyString())).thenThrow(
                new CollectorUnavailableException(null, "collector down", new RuntimeException("connection refused")));
        AgentTrace trace = traceService.start("learn Spring AI");

        assertThatThrownBy(() -> executor.execute("learn Spring AI", trace))
                .isInstanceOf(CollectorUnavailableException.class);

        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isIn("agent_tool_failed", "agent_observation_created");
                    assertThat(call.status()).isEqualTo("FAILED");
                    assertThat(call.errorMessage()).contains("collector down");
                });
    }

    @Test
    void shouldContinueWhenReadmeFetchFailsInRealMode() {
        properties.setMockAgent(false);
        when(collectorClient.searchRepos(anyString(), eq(10), eq("github")))
                .thenReturn(List.of(repo("test", "repo", 0)));
        when(collectorClient.getReadme(anyString(), anyString(), eq("github")))
                .thenThrow(new GitHubApiException(null, "not found", 404, "NOT_FOUND"));
        AgentTrace trace = traceService.start("learn Go");

        AgentRuntimeResult result = executor.execute("learn Go", trace);

        assertThat(result.recommendations()).hasSize(1);
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isEqualTo("readme_fetch_github");
                    assertThat(call.outputSummary()).contains("skipped=1");
                });
        verify(collectorClient, times(1)).getReadme("test", "repo", "github");
    }

    private RepoSummary repo(String owner, String repo, int readmeLength) {
        return new RepoSummary(
                owner,
                repo,
                owner + "/" + repo,
                "A useful project for learning",
                "Java",
                1000,
                100,
                List.of("spring", "ai"),
                "Apache-2.0",
                10,
                Instant.now(),
                Instant.now(),
                readmeLength,
                readmeLength > 0,
                false,
                "mock"
        );
    }
}
