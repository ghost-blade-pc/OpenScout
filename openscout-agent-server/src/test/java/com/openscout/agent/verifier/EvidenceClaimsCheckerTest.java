package com.openscout.agent.verifier;

import com.openscout.agent.ProjectRecommendation;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceClaimsCheckerTest {

    private final EvidenceClaimsChecker checker = new EvidenceClaimsChecker();

    @Test
    void shouldPassWhenDocClaimSupportedByEvidence() {
        var rec = recommendation("owner/repo", List.of("docs:README 5000+ chars", "learning:has examples"));
        String answer = "推荐 **repo**：该项目文档完善，非常适合学习。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isEvidenceClaimsOk()).isTrue();
    }

    @Test
    void shouldWarnWhenDocClaimNotSupported() {
        var rec = recommendation("owner/repo", List.of("activity:recent commits"));
        String answer = "推荐 **repo**：该项目文档完善，README 非常详细。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isEvidenceClaimsOk()).isFalse();
        assertThat(result.getIssues()).anyMatch(i ->
                i.getCheckType().equals("EVIDENCE_UNVERIFIED") && i.getDescription().contains("文档"));
    }

    @Test
    void shouldWarnWhenExampleClaimNotSupported() {
        var rec = recommendation("owner/repo", List.of("docs:README 2000+"));
        String answer = "推荐 **repo**：该项目有丰富的示例代码，方便快速上手。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isEvidenceClaimsOk()).isFalse();
        assertThat(result.getIssues()).anyMatch(i ->
                i.getCheckType().equals("EVIDENCE_UNVERIFIED") && i.getDescription().contains("示例"));
    }

    @Test
    void shouldWarnWhenProductionClaimNotSupported() {
        var rec = recommendation("owner/repo", List.of("docs:README 1000+"));
        String answer = "推荐 **repo**：这是一个生产级的开源项目。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isEvidenceClaimsOk()).isFalse();
        assertThat(result.getIssues()).anyMatch(i ->
                i.getCheckType().equals("EVIDENCE_UNVERIFIED") && i.getDescription().contains("生产级"));
    }

    @Test
    void shouldPassWhenNoClaimsMade() {
        var rec = recommendation("owner/repo", List.of("activity:recent commits"));
        String answer = "推荐 repo，评分 75 分。";

        VerificationResult result = checker.check(answer, List.of(rec));

        assertThat(result.isEvidenceClaimsOk()).isTrue();
    }

    @Test
    void shouldHandleEmptyAnswer() {
        var rec = recommendation("owner/repo", List.of("docs:README"));
        VerificationResult result = checker.check(null, List.of(rec));
        assertThat(result.isEvidenceClaimsOk()).isTrue();

        result = checker.check("", List.of(rec));
        assertThat(result.isEvidenceClaimsOk()).isTrue();
    }

    @Test
    void shouldHandleEmptyRecommendations() {
        VerificationResult result = checker.check("文档完善的项目", Collections.emptyList());
        assertThat(result.isEvidenceClaimsOk()).isTrue();
    }

    private ProjectRecommendation recommendation(String fullName, List<String> evidence) {
        return new ProjectRecommendation(
                fullName, "desc", "Java", 1000L, "2024-01-01",
                new com.openscout.scoring.ProjectScore(75, 20, 15, 25, 10, 5, evidence),
                "reason"
        );
    }
}
