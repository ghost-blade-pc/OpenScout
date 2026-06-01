package com.openscout.agent.tool;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.recommendation.RecommendationScoringService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScoreProjectsTool implements AgentTool {

    public static final String NAME = "score_projects";

    private final RecommendationScoringService recommendationScoringService;

    public ScoreProjectsTool(RecommendationScoringService recommendationScoringService) {
        this.recommendationScoringService = recommendationScoringService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        List<ProjectRecommendation> recommendations = recommendationScoringService.score(
                request.context().getUserGoal(), request.context().getRepos());
        request.context().setRecommendations(recommendations);
        recommendationScoringService.persistReposIfEnabled(request.context().getRepos(), recommendations,
                request.context().getUserGoal(), request.trace());
        return ToolResult.success("recommendations=" + recommendations.size());
    }
}
