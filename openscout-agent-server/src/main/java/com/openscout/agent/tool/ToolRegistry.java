package com.openscout.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> toolsByName;

    public ToolRegistry(List<AgentTool> tools) {
        this.toolsByName = tools.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AgentTool::toolName,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("duplicate toolName: " + left.toolName());
                        }
                ));
    }

    public AgentTool get(String toolName) {
        AgentTool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new IllegalStateException("unsupported tool: " + toolName);
        }
        return tool;
    }
}
