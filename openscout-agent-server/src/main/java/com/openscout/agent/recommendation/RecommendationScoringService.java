package com.openscout.agent.recommendation;

import com.openscout.agent.ProjectRecommendation;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RecommendationScoringService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationScoringService.class);

    private final ProjectScoreService scoreService;
    private final OpenScoutProperties properties;
    private final RepoPersistenceService repoPersistenceService;
    private final RepoAnalysisPersistenceService repoAnalysisPersistenceService;
    private final TraceService traceService;

    public RecommendationScoringService(ProjectScoreService scoreService,
                                        OpenScoutProperties properties,
                                        RepoPersistenceService repoPersistenceService,
                                        RepoAnalysisPersistenceService repoAnalysisPersistenceService,
                                        TraceService traceService) {
        this.scoreService = scoreService;
        this.properties = properties;
        this.repoPersistenceService = repoPersistenceService;
        this.repoAnalysisPersistenceService = repoAnalysisPersistenceService;
        this.traceService = traceService;
    }

    public List<ProjectRecommendation> score(String goal, List<RepoSummary> repos) {
        return repos.stream()
                .map(repo -> ProjectRecommendation.from(repo, scoreService.score(goal, repo), goal))
                .sorted(Comparator.comparing(
                        (ProjectRecommendation item) -> item.score().totalScore()).reversed())
                .toList();
    }

    public void persistReposIfEnabled(List<RepoSummary> repos, List<ProjectRecommendation> recommendations,
                                      String goal, AgentTrace trace) {
        if (!properties.getPersistence().isEnabled()) {
            return;
        }
        Instant start = Instant.now();
        Map<String, ProjectRecommendation> byFullName = recommendations.stream()
                .collect(Collectors.toMap(ProjectRecommendation::fullName, Function.identity(), (left, right) -> left));
        int persisted = 0;
        int failed = 0;
        for (RepoSummary repo : repos) {
            ProjectRecommendation rec = byFullName.get(repo.fullName());
            if (rec == null) {
                continue;
            }
            try {
                repoPersistenceService.upsertRepoInfo(repo);
                String summary = "目标：" + goal + "；推荐理由：" + rec.reason();
                repoAnalysisPersistenceService.saveAnalysis(repo.fullName(), rec.score(), summary);
                persisted++;
            } catch (Exception e) {
                log.warn("repo 持久化失败 full_name={}: {}", repo.fullName(), e.getMessage());
                failed++;
            }
        }
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        if (persisted > 0 || failed > 0) {
            String status = failed > 0 ? "partial" : "ok";
            traceService.recordToolCall(trace, "memory_writeback",
                    "total=" + (persisted + failed),
                    "persisted=" + persisted + " failed=" + failed + " status=" + status,
                    latencyMs);
        }
    }
}
