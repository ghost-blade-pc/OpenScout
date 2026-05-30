package com.openscout.agent;

import com.openscout.client.CollectorClient;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RateLimitException;
import com.openscout.client.ReadmeResponse;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.persistence.analysis.RepoAnalysisPersistenceService;
import com.openscout.persistence.repo.RepoPersistenceService;
import com.openscout.scoring.ProjectScoreService;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OpenScout Agent 编排服务。
 * <p>
 * 根据 {@code openscout.mock-agent} 配置选择 mock 或真实 GitHub 数据源；
 * 根据 LLM 可用性选择自然语言生成或模板回答。
 * 原名 MockAgentService，阶段 6 重命名为 AgentService。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_README_FETCH = 5;
    /** 传给 LLM 回答生成的推荐数上限，控制 prompt 长度避免超时。 */
    private static final int MAX_LLM_RECS = 5;
    private static final Pattern EXAMPLES_PATTERN = Pattern.compile(
            "(?i)\\b(example|sample|demo|tutorial|quickstart)\\b");

    private final CollectorClient collectorClient;
    private final ProjectScoreService scoreService;
    private final TraceService traceService;
    private final OpenScoutProperties properties;
    private final RepoPersistenceService repoPersistenceService;
    private final RepoAnalysisPersistenceService repoAnalysisPersistenceService;
    private final GoalInterpreter goalInterpreter;
    private final AnswerGenerator answerGenerator;

    public AgentService(CollectorClient collectorClient, ProjectScoreService scoreService,
                        TraceService traceService, OpenScoutProperties properties,
                        RepoPersistenceService repoPersistenceService,
                        RepoAnalysisPersistenceService repoAnalysisPersistenceService,
                        GoalInterpreter goalInterpreter,
                        AnswerGenerator answerGenerator) {
        this.collectorClient = collectorClient;
        this.scoreService = scoreService;
        this.traceService = traceService;
        this.properties = properties;
        this.repoPersistenceService = repoPersistenceService;
        this.repoAnalysisPersistenceService = repoAnalysisPersistenceService;
        this.goalInterpreter = goalInterpreter;
        this.answerGenerator = answerGenerator;
    }

    public AgentAskResponse ask(AgentAskRequest request) {
        String question = request.effectiveQuestion();
        if (question.isBlank()) {
            throw new IllegalArgumentException("question or goal must not be blank");
        }
        AgentTrace trace = traceService.start(question);
        try {
            if (properties.isMockAgent()) {
                return askMock(question, trace);
            }
            return askReal(question, trace);
        } catch (RateLimitException ex) {
            traceService.fail(trace, ex);
            String msg = ex.getRetryAfterSeconds() > 0
                    ? "GitHub API 限流，请等待 " + ex.getRetryAfterSeconds() + " 秒后重试"
                    : "GitHub API 限流，请稍后重试或配置 GITHUB_TOKEN 提升限额";
            throw new AgentCallException(trace.getTraceId(), msg, ex);
        } catch (RuntimeException ex) {
            traceService.fail(trace, ex);
            throw new AgentCallException(trace.getTraceId(),
                    "Agent 执行失败，请检查 Go Collector 是否可用：" + ex.getMessage(), ex);
        }
    }

    // ---- mock path ----

    private AgentAskResponse askMock(String question, AgentTrace trace) {
        // use LLM to interpret goal → search keywords
        GoalInterpretation interpretation = goalInterpreter.interpret(question, trace, traceService);
        String keyword = interpretation.keyword();

        Instant toolStart = Instant.now();
        List<RepoSummary> repos = collectorClient.fetchMockRepos(keyword);
        long toolLatencyMs = Duration.between(toolStart, Instant.now()).toMillis();
        traceService.recordToolCall(trace, "repo_search_mock",
                "keyword=" + keyword, "items=" + repos.size(), toolLatencyMs);

        List<ProjectRecommendation> recommendations = scoreAndRank(question, repos);
        persistReposIfEnabled(repos, recommendations, question);

        String scoreSummary = buildScoreSummary(recommendations);
        List<ProjectRecommendation> topForLlm = recommendations.size() > MAX_LLM_RECS
                ? recommendations.subList(0, MAX_LLM_RECS) : recommendations;
        String answer = answerGenerator.generate(question, topForLlm, trace, traceService);
        traceService.complete(trace, scoreSummary, answer);
        return new AgentAskResponse(trace.getTraceId(), answer, recommendations, trace.getLatencyMs());
    }

    // ---- real path ----

    private AgentAskResponse askReal(String question, AgentTrace trace) {
        // use LLM to interpret goal → search keywords
        GoalInterpretation interpretation = goalInterpreter.interpret(question, trace, traceService);
        String keyword = interpretation.keyword();

        // Phase 1: search repos
        Instant searchStart = Instant.now();
        List<RepoSummary> repos = collectorClient.searchRepos(keyword, 10, "github");
        long searchLatencyMs = Duration.between(searchStart, Instant.now()).toMillis();
        traceService.recordToolCall(trace, "repo_search_github",
                "keyword=" + keyword, "items=" + repos.size(), searchLatencyMs);

        // Phase 2: fetch README for top N repos (best-effort)
        List<RepoSummary> enriched = new ArrayList<>();
        int readmeFetched = 0;
        for (int i = 0; i < repos.size() && i < MAX_README_FETCH; i++) {
            RepoSummary repo = repos.get(i);
            RepoSummary enrichedRepo = enrichWithReadme(repo);
            enriched.add(enrichedRepo);
            if (enrichedRepo.readmeLength() > 0) {
                readmeFetched++;
            }
        }
        if (repos.size() > MAX_README_FETCH) {
            enriched.addAll(repos.subList(MAX_README_FETCH, repos.size()));
        }
        traceService.recordToolCall(trace, "readme_fetch_github",
                "targets=" + Math.min(repos.size(), MAX_README_FETCH),
                "fetched=" + readmeFetched, 0);

        List<ProjectRecommendation> recommendations = scoreAndRank(question, enriched);
        persistReposIfEnabled(enriched, recommendations, question);

        String scoreSummary = buildScoreSummary(recommendations);
        List<ProjectRecommendation> topForLlm = recommendations.size() > MAX_LLM_RECS
                ? recommendations.subList(0, MAX_LLM_RECS) : recommendations;
        String answer = answerGenerator.generate(question, topForLlm, trace, traceService);
        traceService.complete(trace, scoreSummary, answer);
        return new AgentAskResponse(trace.getTraceId(), answer, recommendations, trace.getLatencyMs());
    }

    private RepoSummary enrichWithReadme(RepoSummary repo) {
        try {
            ReadmeResponse readme = collectorClient.getReadme(repo.owner(), repo.repo(), "github");
            boolean hasExamples = EXAMPLES_PATTERN.matcher(
                    readme.readme() != null ? readme.readme().substring(0,
                            Math.min(2000, readme.readme().length())) : "").find();
            boolean hasDocker = repo.topics() != null && repo.topics().stream()
                    .anyMatch(t -> "docker".equalsIgnoreCase(t));
            return new RepoSummary(
                    repo.owner(), repo.repo(), repo.fullName(), repo.description(),
                    repo.language(), repo.stars(), repo.forks(), repo.topics(),
                    repo.license(), repo.openIssues(), repo.updatedAt(), repo.pushedAt(),
                    readme.length(), hasExamples, hasDocker, repo.source()
            );
        } catch (GitHubApiException e) {
            log.warn("README fetch skipped for {}: code={} status={}",
                    repo.fullName(), e.getErrorCode(), e.getHttpStatus());
            return repo;
        } catch (RateLimitException e) {
            log.warn("README fetch skipped for {} due to rate limit (retry after {}s)",
                    repo.fullName(), e.getRetryAfterSeconds());
            return repo;
        } catch (Exception e) {
            log.warn("README fetch failed for {}, skipping enrichment: {}", repo.fullName(), e.getMessage());
            return repo;
        }
    }

    // ---- shared helpers ----

    private List<ProjectRecommendation> scoreAndRank(String question, List<RepoSummary> repos) {
        return repos.stream()
                .map(repo -> ProjectRecommendation.from(repo, scoreService.score(question, repo), question))
                .sorted(Comparator.comparing(
                        (ProjectRecommendation item) -> item.score().totalScore()).reversed())
                .toList();
    }

    private String buildScoreSummary(List<ProjectRecommendation> recommendations) {
        return recommendations.stream()
                .map(item -> item.fullName() + "=" + item.score().totalScore())
                .reduce((left, right) -> left + ", " + right)
                .orElse("no recommendations");
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
