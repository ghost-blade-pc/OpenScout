package com.openscout.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.agent.AgentService;
import com.openscout.agent.AnswerGenerator;
import com.openscout.agent.GoalInterpreter;
import com.openscout.agent.react.EvidenceGapDetector;
import com.openscout.agent.react.ReadmeEvidenceEnricher;
import com.openscout.agent.recommendation.RecommendationScoringService;
import com.openscout.agent.runtime.PlanExecutor;
import com.openscout.agent.runtime.RuleBasedAgentPlanner;
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
import com.openscout.client.CollectorClient;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.learning.LearningPlanGenerator;
import com.openscout.memory.ProjectMemoryService;
import com.openscout.persistence.analysis.RepoAnalysisPersistenceService;
import com.openscout.persistence.learning.LearningPlanPersistenceService;
import com.openscout.persistence.repo.RepoPersistenceService;
import com.openscout.scoring.ProjectScoreService;
import com.openscout.trace.TraceService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class EvaluationTestSupport {

    private EvaluationTestSupport() {
    }

    static Harness harness() {
        OpenScoutProperties properties = new OpenScoutProperties();
        properties.getLlm().setEnabled(false);
        properties.getPersistence().setEnabled(false);
        properties.getMemory().setEnabled(true);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CollectorClient collectorClient = mock(CollectorClient.class);
        when(collectorClient.fetchMockRepos(anyString())).thenReturn(List.of(repo("spring-projects", "spring-ai", 2500)));
        TraceService traceService = new TraceService(properties, null);
        ProjectMemoryService memoryService = mock(ProjectMemoryService.class);
        when(memoryService.isEnabled()).thenReturn(true);
        when(memoryService.searchByKeyword(anyString())).thenReturn(List.of());
        when(memoryService.searchByKeyword(argThat(keyword -> keyword != null && keyword.contains("React"))))
                .thenReturn(List.of(repo("facebook", "react", 3200)));
        when(memoryService.getCachedAnalysis(anyString())).thenReturn(Optional.empty());
        when(memoryService.getCachedAnalysis("facebook/react")).thenReturn(Optional.of(
                new ProjectMemoryService.CachedAnalysis(
                        "facebook/react",
                        "React cached profile",
                        88,
                        List.of("docs: README length >= 2000", "learning: examples directory found"),
                        LocalDateTime.now().minusMinutes(5)
                )
        ));
        when(memoryService.isFresh(any(LocalDateTime.class))).thenReturn(true);
        ReadmeEvidenceEnricher readmeEvidenceEnricher = new ReadmeEvidenceEnricher(collectorClient);
        RecommendationScoringService recommendationScoringService = new RecommendationScoringService(
                new ProjectScoreService(),
                properties,
                mock(RepoPersistenceService.class),
                mock(RepoAnalysisPersistenceService.class),
                traceService
        );
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new InterpretGoalTool(new GoalInterpreter(properties, objectMapper), traceService),
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
                new GenerateAnswerTool(new AnswerGenerator(properties), traceService),
                new VerifyAnswerTool(properties, traceService)
        ));
        PlanExecutor planExecutor = new PlanExecutor(
                new RuleBasedAgentPlanner(),
                traceService,
                properties,
                new ToolExecutor(toolRegistry, traceService)
        );
        return new Harness(
                properties,
                collectorClient,
                traceService,
                new AgentService(traceService, planExecutor),
                objectMapper
        );
    }

    private static RepoSummary repo(String owner, String repo, int readmeLength) {
        boolean react = "react".equals(repo);
        return new RepoSummary(
                owner,
                repo,
                owner + "/" + repo,
                react
                        ? "React frontend project with examples and production friendly docs"
                        : "Spring AI Agent sample with examples and production friendly docs",
                react ? "JavaScript" : "Java",
                12_000,
                900,
                react ? List.of("react", "frontend", "javascript", "docker") : List.of("spring", "ai", "agent"),
                "Apache-2.0",
                20,
                Instant.now(),
                Instant.now(),
                readmeLength,
                true,
                true,
                "mock"
        );
    }

    record Harness(
            OpenScoutProperties properties,
            CollectorClient collectorClient,
            TraceService traceService,
            AgentService agentService,
            ObjectMapper objectMapper
    ) {
    }
}
