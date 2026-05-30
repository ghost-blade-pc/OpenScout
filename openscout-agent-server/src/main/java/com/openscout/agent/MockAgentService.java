package com.openscout.agent;

import com.openscout.client.CollectorClient;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.persistence.analysis.RepoAnalysisPersistenceService;
import com.openscout.persistence.repo.RepoPersistenceService;
import com.openscout.scoring.ProjectScore;
import com.openscout.scoring.ProjectScoreService;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class MockAgentService {

    private static final Logger log = LoggerFactory.getLogger(MockAgentService.class);

    private final CollectorClient collectorClient;
    private final ProjectScoreService scoreService;
    private final TraceService traceService;
    private final OpenScoutProperties properties;
    private final RepoPersistenceService repoPersistenceService;
    private final RepoAnalysisPersistenceService repoAnalysisPersistenceService;

    public MockAgentService(CollectorClient collectorClient, ProjectScoreService scoreService,
                            TraceService traceService, OpenScoutProperties properties,
                            RepoPersistenceService repoPersistenceService,
                            RepoAnalysisPersistenceService repoAnalysisPersistenceService) {
        this.collectorClient = collectorClient;
        this.scoreService = scoreService;
        this.traceService = traceService;
        this.properties = properties;
        this.repoPersistenceService = repoPersistenceService;
        this.repoAnalysisPersistenceService = repoAnalysisPersistenceService;
    }

    public AgentAskResponse ask(AgentAskRequest request) {
        String question = request.effectiveQuestion();
        if (question.isBlank()) {
            throw new IllegalArgumentException("question or goal must not be blank");
        }
        AgentTrace trace = traceService.start(question);
        try {
            Instant toolStart = Instant.now();
            List<RepoSummary> repos = collectorClient.fetchMockRepos(question);
            long toolLatencyMs = Duration.between(toolStart, Instant.now()).toMillis();
            traceService.recordToolCall(trace, "repo_search_mock", "keyword=" + question, "items=" + repos.size(), toolLatencyMs);

            List<ProjectRecommendation> recommendations = repos.stream()
                    .map(repo -> ProjectRecommendation.from(repo, scoreService.score(question, repo), question))
                    .sorted(Comparator.comparing((ProjectRecommendation item) -> item.score().totalScore()).reversed())
                    .toList();

            persistReposIfEnabled(repos, recommendations, question);

            String scoreSummary = recommendations.stream()
                    .map(item -> item.fullName() + "=" + item.score().totalScore())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("no recommendations");
            String answer = buildAnswer(question, recommendations);
            traceService.complete(trace, scoreSummary, answer);
            return new AgentAskResponse(trace.getTraceId(), answer, recommendations, trace.getLatencyMs());
        } catch (RuntimeException ex) {
            traceService.fail(trace, ex);
            throw new AgentCallException(trace.getTraceId(), "Agent 执行失败，请检查 Go Collector 是否可用：" + ex.getMessage(), ex);
        }
    }

    private String buildAnswer(String question, List<ProjectRecommendation> recommendations) {
        if (recommendations.isEmpty()) {
            return "暂未找到适合「" + question + "」的候选项目。";
        }
        ProjectRecommendation best = recommendations.get(0);
        return "已基于 mock Agent 完成项目推荐。当前最推荐 " + best.fullName()
                + "，评分 " + best.score().totalScore()
                + "。第一阶段先跑通 Java 调 Go、规则评分和 Trace，后续再接 Spring AI Tool Calling。";
    }

    private void persistReposIfEnabled(List<RepoSummary> repos, List<ProjectRecommendation> recommendations, String goal) {
        if (!properties.getPersistence().isEnabled()) {
            return;
        }
        for (int i = 0; i < repos.size(); i++) {
            RepoSummary repo = repos.get(i);
            ProjectRecommendation rec = recommendations.get(i);
            try {
                repoPersistenceService.upsertRepoInfo(repo);
                String summary = "目标：" + goal + "；推荐理由：" + rec.reason();
                repoAnalysisPersistenceService.saveAnalysis(repo.fullName(), rec.score(), summary);
            } catch (Exception e) {
                log.warn("repo 持久化失败 full_name={}: {}", repo.fullName(), e.getMessage());
            }
        }
    }
}
