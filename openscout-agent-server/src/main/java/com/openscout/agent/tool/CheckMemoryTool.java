package com.openscout.agent.tool;

import com.openscout.client.RepoSummary;
import com.openscout.memory.ProjectMemoryService;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 在 search_repos 前检查 MySQL 中是否已有新鲜的项目数据。
 *
 * <p>命中则预填充 context.repos，让 SearchReposTool 跳过 Collector 调用；
 * 未命中或 memory disabled 则静默继续。</p>
 */
@Component
public class CheckMemoryTool implements AgentTool {

    public static final String NAME = "check_memory";

    private static final Logger log = LoggerFactory.getLogger(CheckMemoryTool.class);

    private final ProjectMemoryService memoryService;
    private final TraceService traceService;

    public CheckMemoryTool(ProjectMemoryService memoryService, TraceService traceService) {
        this.memoryService = memoryService;
        this.traceService = traceService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Instant start = Instant.now();

        // Memory 未启用时静默跳过
        if (!memoryService.isEnabled()) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            traceService.recordToolCall(request.trace(), "memory_check",
                    "keyword=" + request.context().keyword(),
                    "status=disabled",
                    latencyMs);
            return ToolResult.success("memory disabled");
        }

        String keyword = request.context().keyword();
        try {
            List<RepoSummary> cached = memoryService.searchByKeyword(keyword);

            // 过滤新鲜结果
            int totalCached = cached.size();
            List<RepoSummary> fresh = cached.stream()
                    .filter(repo -> {
                        // 按 full_name 查最新分析记录的 freshness
                        return memoryService.getCachedAnalysis(repo.fullName())
                                .map(analysis -> memoryService.isFresh(analysis.analyzedAt()))
                                .orElse(false);
                    })
                    .toList();

            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            if (!fresh.isEmpty()) {
                request.context().setRepos(fresh);
                request.context().setMemoryHit(true);
                request.context().setMemoryRepoCount(fresh.size());
                traceService.recordToolCall(request.trace(), "memory_hit",
                        "keyword=" + keyword + " totalCached=" + totalCached,
                        "fresh=" + fresh.size() + " repos=" + fresh.stream()
                                .map(RepoSummary::fullName)
                                .collect(Collectors.joining(",")),
                        latencyMs);
                log.info("Memory hit: keyword={} fresh={}/{}", keyword, fresh.size(), totalCached);
                return ToolResult.success("hit fresh=" + fresh.size() + " total=" + totalCached);
            }

            String reason = totalCached > 0 ? "all stale (total=" + totalCached + ")" : "no match";
            traceService.recordToolCall(request.trace(), "memory_miss",
                    "keyword=" + keyword,
                    reason,
                    latencyMs);
            log.info("Memory miss: keyword={} reason={}", keyword, reason);
            return ToolResult.success("miss reason=" + reason);

        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            log.warn("Memory check 异常 keyword={}: {}", keyword, e.getMessage());
            traceService.recordToolCall(request.trace(), "memory_check",
                    "keyword=" + keyword,
                    "error=" + e.getMessage(),
                    latencyMs, "SUCCESS", null);
            // continueOnFailure=true，异常不中断 ask
            return ToolResult.success("memory error, continuing");
        }
    }
}
