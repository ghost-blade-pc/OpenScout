package com.openscout.agent.tool;

import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.client.CollectorClient;
import com.openscout.client.RepoSummary;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class SearchReposTool implements AgentTool {

    public static final String NAME = "search_repos";

    private final CollectorClient collectorClient;
    private final TraceService traceService;

    public SearchReposTool(CollectorClient collectorClient, TraceService traceService) {
        this.collectorClient = collectorClient;
        this.traceService = traceService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Instant toolStart = Instant.now();

        // Memory 命中：context 已有 repos，跳过 Collector 调用
        if (request.context().isMemoryHit() && !request.context().getRepos().isEmpty()) {
            int repoCount = request.context().getRepos().size();
            traceService.recordToolCall(request.trace(), "search_repos_skipped",
                    "source=memory",
                    "repos=" + repoCount,
                    Duration.between(toolStart, Instant.now()).toMillis());
            return ToolResult.success("skipped (memory hit) repos=" + repoCount);
        }

        List<RepoSummary> repos;
        String keyword = request.context().keyword();
        if (request.context().getMode() == AgentRuntimeMode.MOCK) {
            repos = collectorClient.fetchMockRepos(keyword);
            traceService.recordToolCall(request.trace(), "repo_search_mock",
                    "keyword=" + keyword, "items=" + repos.size(),
                    Duration.between(toolStart, Instant.now()).toMillis());
        } else {
            repos = collectorClient.searchRepos(keyword, 10, "github");
            traceService.recordToolCall(request.trace(), "repo_search_github",
                    "keyword=" + keyword, "items=" + repos.size(),
                    Duration.between(toolStart, Instant.now()).toMillis());
        }
        request.context().setRepos(repos);
        return ToolResult.success("items=" + repos.size());
    }
}
