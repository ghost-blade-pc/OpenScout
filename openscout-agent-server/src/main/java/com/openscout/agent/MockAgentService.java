package com.openscout.agent;

import com.openscout.client.CollectorClient;
import com.openscout.client.RepoSummary;
import com.openscout.scoring.ProjectScore;
import com.openscout.scoring.ProjectScoreService;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class MockAgentService {

    private final CollectorClient collectorClient;
    private final ProjectScoreService scoreService;
    private final TraceService traceService;

    public MockAgentService(CollectorClient collectorClient, ProjectScoreService scoreService, TraceService traceService) {
        this.collectorClient = collectorClient;
        this.scoreService = scoreService;
        this.traceService = traceService;
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
}
