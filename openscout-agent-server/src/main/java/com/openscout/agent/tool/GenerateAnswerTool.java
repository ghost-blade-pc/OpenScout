package com.openscout.agent.tool;

import com.openscout.agent.AnswerGenerator;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GenerateAnswerTool implements AgentTool {

    public static final String NAME = "generate_answer";

    private static final int MAX_LLM_RECS = 5;

    private final AnswerGenerator answerGenerator;
    private final TraceService traceService;

    public GenerateAnswerTool(AnswerGenerator answerGenerator, TraceService traceService) {
        this.answerGenerator = answerGenerator;
        this.traceService = traceService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        List<ProjectRecommendation> topForLlm = limit(request.context().getRecommendations(), MAX_LLM_RECS);
        String answer = answerGenerator.generate(
                request.context().getUserGoal(), topForLlm, request.trace(), traceService);
        request.context().setAnswer(answer);
        return ToolResult.success("answerLength=" + (answer == null ? 0 : answer.length()));
    }

    private List<ProjectRecommendation> limit(List<ProjectRecommendation> recommendations, int max) {
        return recommendations.size() > max ? recommendations.subList(0, max) : recommendations;
    }
}
