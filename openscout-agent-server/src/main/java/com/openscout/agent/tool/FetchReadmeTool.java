package com.openscout.agent.tool;

import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.client.CollectorClient;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RateLimitException;
import com.openscout.client.ReadmeResponse;
import com.openscout.client.RepoSummary;
import com.openscout.memory.ProjectMemoryService;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class FetchReadmeTool implements AgentTool {

    public static final String NAME = "fetch_readme";

    private static final Logger log = LoggerFactory.getLogger(FetchReadmeTool.class);
    private static final int MAX_README_FETCH = 5;
    /** 匹配 README 正文中的示例/教程关键词，支持单复数形式。 */
    private static final Pattern EXAMPLES_PATTERN = Pattern.compile(
            "(?i)\\b(example[s]?|sample[s]?|demo[s]?|tutorial[s]?|quickstart)\\b");

    private final CollectorClient collectorClient;
    private final TraceService traceService;
    private final ProjectMemoryService memoryService;

    public FetchReadmeTool(CollectorClient collectorClient, TraceService traceService,
                           ProjectMemoryService memoryService) {
        this.collectorClient = collectorClient;
        this.traceService = traceService;
        this.memoryService = memoryService;
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
        for (int i = 0; i < targets; i++) {
            RepoSummary repo = repos.get(i);
            // 先尝试 memory 缓存
            if (memoryService.isEnabled()) {
                var cached = memoryService.getCachedAnalysis(repo.fullName());
                if (cached.isPresent() && memoryService.isFresh(cached.get().analyzedAt())) {
                    // Memory 有新鲜分析记录，从 evidence 推断 hasExamples / readmeLength
                    List<String> evidence = cached.get().evidence();
                    boolean hasExamples = evidence.stream()
                            .anyMatch(e -> EXAMPLES_PATTERN.matcher(e).find());
                    int readmeLen = inferReadmeLength(evidence);
                    boolean hasDocker = hasDockerTopic(repo);
                    RepoSummary memRepo = new RepoSummary(
                            repo.owner(), repo.repo(), repo.fullName(), repo.description(),
                            repo.language(), repo.stars(), repo.forks(), repo.topics(),
                            repo.license(), repo.openIssues(), repo.updatedAt(), repo.pushedAt(),
                            readmeLen, hasExamples, hasDocker, "cache"
                    );
                    enriched.add(memRepo);
                    cacheHit++;
                    traceService.recordToolCall(request.trace(), "readme_cache_hit",
                            "repo=" + repo.fullName(),
                            "freshness=" + cached.get().analyzedAt(),
                            0);
                    continue;
                }
            }
            // Memory 未命中或过期，调用 GitHub
            EnrichedRepo result = enrichWithReadme(repo);
            enriched.add(result.repo());
            if (result.fetched()) {
                fetched++;
            } else {
                skipped++;
            }
        }
        if (repos.size() > MAX_README_FETCH) {
            enriched.addAll(repos.subList(MAX_README_FETCH, repos.size()));
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

    private EnrichedRepo enrichWithReadme(RepoSummary repo) {
        try {
            ReadmeResponse readme = collectorClient.getReadme(repo.owner(), repo.repo(), "github");
            String readmeText = readme.readme() != null ? readme.readme() : "";
            boolean hasExamples = EXAMPLES_PATTERN.matcher(
                    readmeText.substring(0, Math.min(2000, readmeText.length()))).find();
            boolean hasDocker = hasDockerTopic(repo);
            RepoSummary enriched = new RepoSummary(
                    repo.owner(), repo.repo(), repo.fullName(), repo.description(),
                    repo.language(), repo.stars(), repo.forks(), repo.topics(),
                    repo.license(), repo.openIssues(), repo.updatedAt(), repo.pushedAt(),
                    readme.length(), hasExamples, hasDocker, repo.source()
            );
            return new EnrichedRepo(enriched, readme.length() > 0);
        } catch (GitHubApiException e) {
            log.warn("README fetch skipped for {}: code={} status={}",
                    repo.fullName(), e.getErrorCode(), e.getHttpStatus());
            return new EnrichedRepo(repo, false);
        } catch (RateLimitException e) {
            log.warn("README fetch skipped for {} due to rate limit (retry after {}s)",
                    repo.fullName(), e.getRetryAfterSeconds());
            return new EnrichedRepo(repo, false);
        } catch (Exception e) {
            log.warn("README fetch failed for {}, skipping enrichment: {}", repo.fullName(), e.getMessage());
            return new EnrichedRepo(repo, false);
        }
    }

    /**
     * 从 scoring evidence 推断原始 README 长度。
     *
     * <p>当从 memory 缓存恢复时，原始 README 长度已丢失。
     * 根据既有 evidence 字符串推断一个能命中评分阈值的最小值：</p>
     * <ul>
     *   <li>"docs: README length &gt;= 2000" → 2000（命中 docScore +12 和 learningScore +6 阈值）</li>
     *   <li>"docs: README exists but is short" → 500（命中 docScore +6 阈值，但不到 1000/2000）</li>
     *   <li>其他 → 0</li>
     * </ul>
     */
    static int inferReadmeLength(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0;
        }
        for (String e : evidence) {
            if (e.contains("README length >= 2000")) {
                return 2000;
            }
            if (e.contains("README exists but is short")) {
                return 500;
            }
        }
        return 0;
    }

    private static boolean hasDockerTopic(RepoSummary repo) {
        return repo.topics() != null && repo.topics().stream()
                .anyMatch(t -> "docker".equalsIgnoreCase(t));
    }

    private record EnrichedRepo(RepoSummary repo, boolean fetched) {
    }
}
