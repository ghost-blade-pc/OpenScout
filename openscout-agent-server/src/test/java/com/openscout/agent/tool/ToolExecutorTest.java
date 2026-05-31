package com.openscout.agent.tool;

import com.openscout.agent.runtime.AgentContext;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.agent.runtime.PlanStep;
import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import com.openscout.trace.TraceToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutorTest {

    @Test
    void shouldRecordStartedAndFinishedWhenToolSucceeds() {
        TraceService traceService = new TraceService(new OpenScoutProperties(), null);
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(new TestTool("search_repos", ToolResult.success("items=1"), null))),
                traceService);
        AgentTrace trace = traceService.start("question");

        ToolResult result = executor.execute(request("search_repos", trace));

        assertThat(result.outputSummary()).isEqualTo("items=1");
        assertThat(trace.getToolCalls()).extracting(TraceToolCall::toolName)
                .contains("agent_tool_started", "agent_tool_finished");
    }

    @Test
    void shouldRecordFailedAndRethrowWhenToolThrows() {
        TraceService traceService = new TraceService(new OpenScoutProperties(), null);
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(new TestTool("search_repos", null, new IllegalStateException("down")))),
                traceService);
        AgentTrace trace = traceService.start("question");

        assertThatThrownBy(() -> executor.execute(request("search_repos", trace)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("down");
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isEqualTo("agent_tool_failed");
                    assertThat(call.status()).isEqualTo("FAILED");
                    assertThat(call.errorMessage()).contains("down");
                });
    }

    @Test
    void shouldRecordRecoverableFailureResultWithoutThrowing() {
        TraceService traceService = new TraceService(new OpenScoutProperties(), null);
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(new TestTool("fetch_readme",
                        ToolResult.recoverableFailure("skipped=1", "not found"), null))),
                traceService);
        AgentTrace trace = traceService.start("question");

        ToolResult result = executor.execute(request("fetch_readme", trace));

        assertThat(result.errorSummary()).isEqualTo("not found");
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isEqualTo("agent_tool_failed");
                    assertThat(call.status()).isEqualTo("FAILED");
                    assertThat(call.outputSummary()).contains("skipped=1");
                });
    }

    @Test
    void shouldRejectDuplicateToolNames() {
        AgentTool left = new TestTool("search_repos", ToolResult.success("left"), null);
        AgentTool right = new TestTool("search_repos", ToolResult.success("right"), null);

        assertThatThrownBy(() -> new ToolRegistry(List.of(left, right)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate toolName");
    }

    @Test
    void shouldRecordFailureForUnknownTool() {
        TraceService traceService = new TraceService(new OpenScoutProperties(), null);
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of()), traceService);
        AgentTrace trace = traceService.start("question");

        assertThatThrownBy(() -> executor.execute(request("missing_tool", trace)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported tool");
        assertThat(trace.getToolCalls())
                .anySatisfy(call -> {
                    assertThat(call.toolName()).isEqualTo("agent_tool_failed");
                    assertThat(call.errorMessage()).contains("unsupported tool");
                });
    }

    private ToolRequest request(String toolName, AgentTrace trace) {
        return new ToolRequest(
                new PlanStep("step-1", toolName, "purpose", "input", false),
                new AgentContext("question", AgentRuntimeMode.MOCK),
                trace
        );
    }

    private record TestTool(String toolName, ToolResult result, RuntimeException exception) implements AgentTool {
        @Override
        public ToolResult execute(ToolRequest request) {
            if (exception != null) {
                throw exception;
            }
            return result;
        }
    }
}
