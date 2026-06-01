package com.openscout.agent.tool;

import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.agent.react.ReadmeEnrichmentResult;
import com.openscout.agent.react.ReadmeEvidenceEnricher;
import com.openscout.client.RepoSummary;
import com.openscout.memory.ProjectMemoryService;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class FetchReadmeTool implements AgentTool {

    public static final String NAME = "fetch_readme";

    private static final int MAX_README_FETCH = 5;

    private final TraceService traceService;
    private final ProjectMemoryService memoryService;
    private final ReadmeEvidenceEnricher readmeEvidenceEnricher;

    public FetchReadmeTool(TraceService traceService,
                           ProjectMemoryService memoryService,
                           ReadmeEvidenceEnricher readmeEvidenceEnricher) {
        this.traceService = traceService;
        this.memoryService = memoryService;
        this.readmeEvidenceEnricher = readmeEvidenceEnricher;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        if (request.context().getMode() != AgentRuntimeMode.REAL) {
            return ToolResult.success("skipped mode=" + request.context().getMode());
        }
        Instant start = Instant.now();
        List<RepoSummary> repos = request.context().getRepos();
        List<RepoSummary> enriched = new ArrayList<>();
        int targets = Math.min(repos.size(), MAX_README_FETCH);
        int fetched = 0;
        int skipped = 0;
        int cacheHit = 0;
        int processed = 0;
        for (int i = 0; i < targets; i++) {
            RepoSummary repo = repos.get(i);
            // 先尝试 memory 缓存
            if (memoryService.isEnabled()) {
                var cached = memoryService.getCachedAnalysis(repo.fullName());
                if (cached.isPresent() && memoryService.isFresh(cached.get().analyzedAt())) {
                    // Memory 有新鲜分析记录，从 evidence 推断 hasExamples / readmeLength
                    List<String> evidence = cached.get().evidence();
                    boolean hasExamples = ReadmeEvidenceEnricher.hasExamplesEvidence(evidence);
                    int readmeLen = ReadmeEvidenceEnricher.inferReadmeLength(evidence);
                    boolean hasDocker = ReadmeEvidenceEnricher.hasDockerTopic(repo);
                    RepoSummary memRepo = new RepoSummary(
                            repo.owner(), repo.repo(), repo.fullName(), repo.description(),
                            repo.language(), repo.stars(), repo.forks(), repo.topics(),
                            repo.license(), repo.openIssues(), repo.updatedAt(), repo.pushedAt(),
                            readmeLen, hasExamples, hasDocker, "cache"
                    );
                    enriched.add(memRepo);
                    if (readmeLen > 0) {
                        request.context().clearReadmeFailure(repo.fullName());
                    }
                    cacheHit++;
                    traceService.recordToolCall(request.trace(), "readme_cache_hit",
                            "repo=" + repo.fullName(),
                            "freshness=" + cached.get().analyzedAt(),
                            0);
                    processed = i + 1;
                    continue;
                }
            }
            // Memory 未命中或过期，调用 GitHub
            ReadmeEnrichmentResult result = readmeEvidenceEnricher.enrichFromGitHub(repo);
            enriched.add(result.repo());
            processed = i + 1;
            if (result.fetched()) {
                request.context().clearReadmeFailure(repo.fullName());
                fetched++;
            } else {
                request.context().recordReadmeFailure(repo.fullName(), result.status(), result.retryAfterSeconds());
                skipped++;
                if (result.rateLimited()) {
                    break;
                }
            }
        }
        if (repos.size() > processed) {
            enriched.addAll(repos.subList(processed, repos.size()));
        }
        request.context().setRepos(enriched);
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        traceService.recordToolCall(request.trace(), "readme_fetch_github",
                "targets=" + targets,
                "fetched=" + fetched + " skipped=" + skipped + " cacheHit=" + cacheHit,
                latencyMs);
        return ToolResult.success("targets=" + targets + " fetched=" + fetched
                + " skipped=" + skipped + " cacheHit=" + cacheHit);
    }
}
