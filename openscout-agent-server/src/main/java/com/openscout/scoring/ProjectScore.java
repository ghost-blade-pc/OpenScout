package com.openscout.scoring;

import java.util.List;

public record ProjectScore(
        int totalScore,
        int activityScore,
        int docScore,
        int matchScore,
        int learningScore,
        int resumeValueScore,
        List<String> evidence
) {
}
