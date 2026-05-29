package com.openscout.agent;

import java.util.List;

public record AgentAskResponse(
        String traceId,
        String answer,
        List<ProjectRecommendation> recommendations,
        long latencyMs
) {
}
