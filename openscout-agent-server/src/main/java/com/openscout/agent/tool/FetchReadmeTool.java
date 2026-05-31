package com.openscout.agent.tool;

import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.client.CollectorClient;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RateLimitException;
import com.openscout.client.ReadmeResponse;
import com.openscout.client.RepoSummary;
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
    private static final Pattern EXAMPLES_PATTERN = Pattern.compile(
            "(?i)\\b(example|sample|demo|tutorial|quickstart)\\b");

    private final CollectorClient collectorClient;
    private final TraceService traceService;

    public FetchReadmeTool(CollectorClient collectorClient, TraceService traceService) {
        this.collectorClient = collectorClient;
        this.traceService = traceService;
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
        for (int i = 0; i < targets; i++) {
            EnrichedRepo result = enrichWithReadme(repos.get(i));
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
                "fetched=" + fetched + " skipped=" + skipped,
                latencyMs);
        return ToolResult.success("targets=" + targets + " fetched=" + fetched + " skipped=" + skipped);
    }

    private EnrichedRepo enrichWithReadme(RepoSummary repo) {
        try {
            ReadmeResponse readme = collectorClient.getReadme(repo.owner(), repo.repo(), "github");
            String readmeText = readme.readme() != null ? readme.readme() : "";
            boolean hasExamples = EXAMPLES_PATTERN.matcher(
                    readmeText.substring(0, Math.min(2000, readmeText.length()))).find();
            boolean hasDocker = repo.topics() != null && repo.topics().stream()
                    .anyMatch(t -> "docker".equalsIgnoreCase(t));
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

    private record EnrichedRepo(RepoSummary repo, boolean fetched) {
    }
}
