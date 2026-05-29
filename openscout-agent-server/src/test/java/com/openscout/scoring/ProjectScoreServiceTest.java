package com.openscout.scoring;

import com.openscout.client.RepoSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectScoreServiceTest {

    private final ProjectScoreService scoreService = new ProjectScoreService();

    @Test
    void scoreShouldBeStableAndEvidenceBacked() {
        RepoSummary repo = new RepoSummary(
                "spring-projects",
                "spring-ai",
                "spring-projects/spring-ai",
                "Spring AI application framework",
                "Java",
                12000,
                2000,
                List.of("spring", "ai", "llm"),
                "Apache-2.0",
                120,
                Instant.now(),
                Instant.now(),
                4500,
                true,
                true,
                "mock"
        );

        ProjectScore score = scoreService.score("Spring AI Agent Java", repo);

        assertThat(score.totalScore()).isBetween(70, 100);
        assertThat(score.evidence()).isNotEmpty();
        assertThat(score.matchScore()).isGreaterThan(0);
        assertThat(score.docScore()).isGreaterThan(0);
    }
}
