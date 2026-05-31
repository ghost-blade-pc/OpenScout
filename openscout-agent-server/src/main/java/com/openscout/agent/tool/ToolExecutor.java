package com.openscout.agent.tool;

import com.openscout.agent.runtime.PlanStep;
import com.openscout.agent.runtime.PlanStepStatus;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final TraceService traceService;

    public ToolExecutor(ToolRegistry toolRegistry, TraceService traceService) {
        this.toolRegistry = toolRegistry;
        this.traceService = traceService;
    }

    public ToolResult execute(ToolRequest request) {
        PlanStep step = request.step();
        traceService.recordToolStarted(request.trace(), step);
        Instant start = Instant.now();
        try {
            AgentTool tool = toolRegistry.get(step.getToolName());
            ToolResult result = tool.execute(request);
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            ToolResult timedResult = result.withLatency(latencyMs);
            if (timedResult.status() == PlanStepStatus.FAILED) {
                traceService.recordToolFailed(request.trace(), step, timedResult, latencyMs);
            } else {
                traceService.recordToolFinished(request.trace(), step, timedResult, latencyMs);
            }
            return timedResult;
        } catch (RuntimeException ex) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            traceService.recordToolFailed(request.trace(), step,
                    ToolResult.recoverableFailure(null, ex.getMessage()), latencyMs);
            throw ex;
        }
    }
}
