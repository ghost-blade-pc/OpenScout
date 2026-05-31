package com.openscout.agent.tool;

import com.openscout.agent.runtime.AgentContext;
import com.openscout.agent.runtime.PlanStep;
import com.openscout.trace.AgentTrace;

public record ToolRequest(
        PlanStep step,
        AgentContext context,
        AgentTrace trace
) {
}
