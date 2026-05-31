package com.openscout.agent.tool;

public interface AgentTool {

    String toolName();

    ToolResult execute(ToolRequest request);
}
