package com.openscout.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.agent.AnswerGenerator;
import com.openscout.agent.GoalInterpreter;
import com.openscout.client.CollectorClient;
import com.openscout.client.CollectorUnavailableException;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.learning.LearningPlanGenerator;
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
        collectorClient = mock(CollectorClient.class);
        traceService = new TraceService(properties, null);
        executor = new PlanExecutor(
                new RuleBasedAgentPlanner(),
                collectorClient,
                new ProjectScoreService(),
                traceService,
                properties,
                mock(RepoPersistenceService.class),
                mock(RepoAnalysisPersistenceService.class),
                new GoalInterpreter(properties, new ObjectMapper()),
                new AnswerGenerator(properties),
                new LearningPlanGenerator(properties, new ObjectMapper()),
                mock(LearningPlanPersistenceService.class)
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
                        "agent_observation_created", "repo_search_mock", "learning_plan_generate");
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
                    assertThat(call.toolName()).isEqualTo("agent_observation_created");
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
