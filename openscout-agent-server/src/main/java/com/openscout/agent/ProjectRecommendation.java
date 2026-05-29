package com.openscout.agent;

import com.openscout.client.RepoSummary;
import com.openscout.scoring.ProjectScore;

public record ProjectRecommendation(
        String fullName,
        String description,
        String language,
        long stars,
        String updatedAt,
        ProjectScore score,
        String reason
) {

    public static ProjectRecommendation from(RepoSummary repo, ProjectScore score, String goal) {
        String reason = "项目与目标「" + goal + "」的匹配分为 " + score.totalScore()
                + "，主要依据：" + String.join("; ", score.evidence());
        return new ProjectRecommendation(
                repo.fullName(),
                repo.description(),
                repo.language(),
                repo.stars(),
                repo.updatedAt() == null ? null : repo.updatedAt().toString(),
                score,
                reason
        );
    }
}
