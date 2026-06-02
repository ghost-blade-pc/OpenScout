package com.openscout.agent.verifier;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.learning.LearningPlanResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreIntegrityCheckerTest {

    private final ScoreIntegrityChecker checker = new ScoreIntegrityChecker();

    @Test
    void shouldPassWhenScoreMatches() {
        var rec = recommendation("owner/repo", 75);
        String answer = "推荐 **owner/repo**：该项目评分 75 分，综合表现优秀。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isScoreIntegrityOk()).isTrue();
        assertThat(result.getIssues()).isEmpty();
    }

    @Test
    void shouldDetectScoreTampering() {
        var rec = recommendation("owner/repo", 75);
        String answer = "推荐 **owner/repo**：该项目评分 80 分，综合表现优秀。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isScoreIntegrityOk()).isFalse();
        assertThat(result.getIssues()).anyMatch(i -> i.getCheckType().equals("SCORE_TAMPERING"));
    }

    @Test
    void shouldHandleScoreWithSlashFormat() {
        var rec = recommendation("owner/repo", 75);
        String answer = "owner/repo 总分 75/100，表现良好。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isScoreIntegrityOk()).isTrue();
    }

    @Test
    void shouldWarnWhenNoScoreMentioned() {
        var rec = recommendation("owner/repo", 75);
        String answer = "推荐 owner/repo，该项目是一个优秀的开源项目。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isScoreIntegrityOk()).isFalse();
        assertThat(result.getIssues()).anyMatch(i ->
                i.getCheckType().equals("SCORE_FORMAT_UNRECOGNIZED"));
    }

    @Test
    void shouldHandleEmptyAnswer() {
        var rec = recommendation("owner/repo", 75);
        VerificationResult result = checker.check(null, List.of(rec));
        assertThat(result.isScoreIntegrityOk()).isTrue();

        result = checker.check("", List.of(rec));
        assertThat(result.isScoreIntegrityOk()).isTrue();
    }

    @Test
    void shouldHandleEmptyRecommendations() {
        VerificationResult result = checker.check("评分 75 分", Collections.emptyList());
        assertThat(result.isScoreIntegrityOk()).isTrue();
    }

    @Test
    void shouldDetectTamperingInMultipleRepos() {
        var rec1 = recommendation("owner/repo1", 80);
        var rec2 = recommendation("owner/repo2", 60);
        String answer = "推荐 **repo1** 评分 80 分，**repo2** 评分 70 分。";

        VerificationResult result = checker.check(answer, List.of(rec1, rec2));

        assertThat(result.isScoreIntegrityOk()).isFalse();
        assertThat(result.getIssues()).hasSize(1);
        assertThat(result.getIssues().get(0).getDetail()).contains("repo2");
    }

    @Test
    void shouldSkipRepoNotMentionedInAnswer() {
        // LLM 回答仅覆盖 Top 5 项目，其余 5 个不在回答中 → 应跳过而不是报 issue
        var mentioned = recommendation("owner/repo1", 80);
        var notMentioned = recommendation("other/repo2", 60);
        String answer = "推荐 **repo1** 评分 80 分，非常优秀。";

        VerificationResult result = checker.check(answer, List.of(mentioned, notMentioned));

        assertThat(result.isScoreIntegrityOk()).isTrue();
        assertThat(result.getIssues()).isEmpty();
    }

    @Test
    void shouldExtractScoreWithPrefixWords() {
        var rec = recommendation("owner/spring-ai", 82);
        String answer = "推荐 owner/spring-ai：综合评分：82 分（满分 100）";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isScoreIntegrityOk()).isTrue();
    }

    private ProjectRecommendation recommendation(String fullName, int totalScore) {
        return new ProjectRecommendation(
                fullName, "desc", "Java", 1000L, "2024-01-01",
                new com.openscout.scoring.ProjectScore(totalScore, 20, 15, 25, 15, 7,
                        List.of("docs:README 3000+", "learning:has examples")),
                "reason"
        );
    }
}
