package com.openscout.agent.tool;

import com.openscout.agent.GoalInterpretation;
import com.openscout.agent.GoalInterpreter;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Component;

@Component
public class InterpretGoalTool implements AgentTool {

    public static final String NAME = "interpret_goal";

    private final GoalInterpreter goalInterpreter;
    private final TraceService traceService;

    public InterpretGoalTool(GoalInterpreter goalInterpreter, TraceService traceService) {
        this.goalInterpreter = goalInterpreter;
        this.traceService = traceService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        GoalInterpretation interpretation = goalInterpreter.interpret(
                request.context().getUserGoal(), request.trace(), traceService);
        request.context().setInterpretation(interpretation);
        return ToolResult.success("keyword=" + interpretation.keyword());
    }
}
