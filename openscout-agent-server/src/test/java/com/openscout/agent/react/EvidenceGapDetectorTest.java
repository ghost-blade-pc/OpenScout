package com.openscout.agent.react;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.client.RepoSummary;
import com.openscout.scoring.ProjectScoreService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGapDetectorTest {

    private final EvidenceGapDetector detector = new EvidenceGapDetector();
    private final ProjectScoreService scoreService = new ProjectScoreService();

    @Test
    void shouldDetectMissingReadme() {
        RepoSummary repo = repo("example", "missing-readme", 0, false, "github");
        ProjectRecommendation rec = recommendation(repo);

        List<EvidenceGap> gaps = detector.detect(List.of(repo), List.of(rec), 3);

        assertThat(gaps).singleElement()
                .satisfies(gap -> {
                    assertThat(gap.fullName()).isEqualTo("example/missing-readme");
                    assertThat(gap.gapType()).isEqualTo(EvidenceGapType.MISSING_README);
                    assertThat(gap.action()).isEqualTo("fetch_readme");
                });
    }

    @Test
    void shouldSkipWhenEvidenceIsStrongEnough() {
        RepoSummary repo = repo("example", "strong-docs", 2500, true, "github");
        ProjectRecommendation rec = recommendation(repo);

        List<EvidenceGap> gaps = detector.detect(List.of(repo), List.of(rec), 3);

        assertThat(gaps).isEmpty();
    }

    @Test
    void shouldLimitTopRecommendations() {
        List<RepoSummary> repos = List.of(
                repo("example", "one", 0, false, "github"),
                repo("example", "two", 0, false, "github"),
                repo("example", "three", 0, false, "github"),
                repo("example", "four", 0, false, "github")
        );
        List<ProjectRecommendation> recommendations = repos.stream()
                .map(this::recommendation)
                .toList();

        List<EvidenceGap> gaps = detector.detect(repos, recommendations, 2);

        assertThat(gaps).hasSize(2);
        assertThat(gaps).extracting(EvidenceGap::fullName)
                .containsExactly("example/one", "example/two");
    }

    @Test
    void shouldDetectIncompleteCacheEvidence() {
        RepoSummary repo = repo("example", "cached", 500, false, "cache");
        ProjectRecommendation rec = recommendation(repo);

        List<EvidenceGap> gaps = detector.detect(List.of(repo), List.of(rec), 3);

        assertThat(gaps).singleElement()
                .extracting(EvidenceGap::gapType)
                .isEqualTo(EvidenceGapType.CACHE_EVIDENCE_INCOMPLETE);
    }

    private ProjectRecommendation recommendation(RepoSummary repo) {
        return ProjectRecommendation.from(repo, scoreService.score("learn Java", repo), "learn Java");
    }

    private RepoSummary repo(String owner, String name, int readmeLength, boolean hasExamples, String source) {
        return new RepoSummary(
                owner,
                name,
                owner + "/" + name,
                "A Java learning project",
                "Java",
                1000,
                100,
                List.of("java", "spring"),
                "Apache-2.0",
                10,
                Instant.now(),
                Instant.now(),
                readmeLength,
                hasExamples,
                false,
                source
        );
    }
}
