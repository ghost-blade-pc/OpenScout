package com.openscout.agent.tool;

import com.openscout.agent.runtime.AgentContext;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.agent.runtime.PlanStep;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.memory.ProjectMemoryService;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import com.openscout.trace.TraceToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckMemoryToolTest {

    private OpenScoutProperties properties;
    private TraceService traceService;
    private ProjectMemoryService memoryService;
    private CheckMemoryTool tool;

    @BeforeEach
    void setUp() {
        properties = new OpenScoutProperties();
        properties.getPersistence().setEnabled(true);
        traceService = new TraceService(properties, null);
        memoryService = mock(ProjectMemoryService.class);
        when(memoryService.isEnabled()).thenReturn(true);
        when(memoryService.getFreshnessHours()).thenReturn(24);
        when(memoryService.isFresh(null)).thenReturn(false);
        tool = new CheckMemoryTool(memoryService, traceService);
    }

    @Test
    void shouldHitWhenFreshResultsFound() {
        RepoSummary repo = repo("facebook", "react");
        when(memoryService.searchByKeyword(anyString())).thenReturn(List.of(repo));
        var cached = mock(ProjectMemoryService.CachedAnalysis.class);
        when(cached.analyzedAt()).thenReturn(java.time.LocalDateTime.now().minusHours(1));
        when(memoryService.getCachedAnalysis("facebook/react"))
                .thenReturn(java.util.Optional.of(cached));
        when(memoryService.isFresh(any(java.time.LocalDateTime.class))).thenReturn(true);
        AgentTrace trace = traceService.start("learn React");
        AgentContext context = new AgentContext("learn React", AgentRuntimeMode.MOCK);
        context.setInterpretation(new com.openscout.agent.GoalInterpretation("react", "JavaScript", "frontend"));

        ToolResult result = tool.execute(new ToolRequest(
                new PlanStep("step-2", "check_memory", "purpose", "input", true),
                context, trace));

        assertThat(result.outputSummary()).contains("hit");
        assertThat(context.isMemoryHit()).isTrue();
        assertThat(context.getMemoryRepoCount()).isEqualTo(1);
        assertThat(context.getRepos()).hasSize(1);
        assertThat(trace.getToolCalls()).extracting(TraceToolCall::toolName)
                .contains("memory_hit");
    }

    @Test
    void shouldMissWhenNoResultsFound() {
        when(memoryService.searchByKeyword(anyString())).thenReturn(List.of());
        AgentTrace trace = traceService.start("learn xyz");
        AgentContext context = new AgentContext("learn xyz", AgentRuntimeMode.MOCK);
        context.setInterpretation(new com.openscout.agent.GoalInterpretation("xyz", "Unknown", "unknown"));

        ToolResult result = tool.execute(new ToolRequest(
                new PlanStep("step-2", "check_memory", "purpose", "input", true),
                context, trace));

        assertThat(result.outputSummary()).contains("miss");
        assertThat(context.isMemoryHit()).isFalse();
        assertThat(context.getRepos()).isEmpty();
        assertThat(trace.getToolCalls()).extracting(TraceToolCall::toolName)
                .contains("memory_miss");
    }

    @Test
    void shouldMissWhenAllResultsStale() {
        RepoSummary repo = repo("old", "project");
        when(memoryService.searchByKeyword(anyString())).thenReturn(List.of(repo));
        var cached = mock(ProjectMemoryService.CachedAnalysis.class);
        when(cached.analyzedAt()).thenReturn(java.time.LocalDateTime.now().minusHours(25));
        when(memoryService.getCachedAnalysis("old/project"))
                .thenReturn(java.util.Optional.of(cached));
        when(memoryService.isFresh(any(java.time.LocalDateTime.class))).thenReturn(false);
        AgentTrace trace = traceService.start("learn old");
        AgentContext context = new AgentContext("learn old", AgentRuntimeMode.MOCK);
        context.setInterpretation(new com.openscout.agent.GoalInterpretation("old", "Unknown", "unknown"));

        ToolResult result = tool.execute(new ToolRequest(
                new PlanStep("step-2", "check_memory", "purpose", "input", true),
                context, trace));

        assertThat(result.outputSummary()).contains("miss");
        assertThat(context.isMemoryHit()).isFalse();
        assertThat(context.getRepos()).isEmpty();
    }

    @Test
    void shouldSkipWhenMemoryDisabled() {
        when(memoryService.isEnabled()).thenReturn(false);
        AgentTrace trace = traceService.start("learn React");
        AgentContext context = new AgentContext("learn React", AgentRuntimeMode.MOCK);

        ToolResult result = tool.execute(new ToolRequest(
                new PlanStep("step-2", "check_memory", "purpose", "input", true),
                context, trace));

        assertThat(result.outputSummary()).contains("disabled");
        assertThat(context.isMemoryHit()).isFalse();
        assertThat(trace.getToolCalls()).extracting(TraceToolCall::toolName)
                .contains("memory_check");
    }

    @Test
    void shouldContinueOnException() {
        when(memoryService.searchByKeyword(anyString()))
                .thenThrow(new RuntimeException("DB down"));
        AgentTrace trace = traceService.start("learn React");
        AgentContext context = new AgentContext("learn React", AgentRuntimeMode.MOCK);

        // continueOnFailure=true → should not throw
        ToolResult result = tool.execute(new ToolRequest(
                new PlanStep("step-2", "check_memory", "purpose", "input", true),
                context, trace));

        assertThat(result.outputSummary()).contains("error");
        assertThat(context.isMemoryHit()).isFalse();
    }

    private RepoSummary repo(String owner, String name) {
        return new RepoSummary(
                owner,
                name,
                owner + "/" + name,
                "A test repo",
                "JavaScript",
                1000,
                100,
                List.of("web"),
                "MIT",
                10,
                Instant.now(),
                Instant.now(),
                0,
                false,
                false,
                "cache"
        );
    }
}
