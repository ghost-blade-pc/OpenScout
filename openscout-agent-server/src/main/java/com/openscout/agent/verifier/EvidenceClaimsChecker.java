package com.openscout.agent.verifier;

import com.openscout.agent.ProjectRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class EvidenceClaimsChecker {

    private static final Logger log = LoggerFactory.getLogger(EvidenceClaimsChecker.class);

    private static final Set<String> DOCUMENTED_CLAIMS = Set.of(
            "完善的文档", "详细文档", "文档完善", "文档齐全", "文档丰富",
            "完善的 readme", "详细说明", "说明文档",
            "documentation", "well-documented", "comprehensive documentation",
            "well documented", "detailed documentation"
    );

    private static final Set<String> EXAMPLE_CLAIMS = Set.of(
            "示例代码", "示例项目", "代码示例", "有示例", "有 demo",
            "快速入门",
            "quick start", "quick-start", "quickstart",
            "demo project", "demos",
            "example", "examples", "示例",
            "tutorial", "tutorials"
    );

    private static final Set<String> PRODUCTION_CLAIMS = Set.of(
            "生产级", "企业级", "工业级", "生产环境",
            "production-ready", "enterprise-grade",
            "production ready", "enterprise grade",
            "production grade", "production-grade"
    );

    private static final Pattern DOC_DOCS_EVIDENCE = Pattern.compile("docs:", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEARNING_EXAMPLE_EVIDENCE = Pattern.compile(
            "learning:|examples|has.?examples|\\bdemo\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MATCH_EVIDENCE = Pattern.compile("match:", Pattern.CASE_INSENSITIVE);

    private static final Pattern NEGATION_PATTERN = Pattern.compile(
            "(?:不|没有|缺少|无|非|未|欠|不是|不太|并不|绝不|毫不|没啥|没)"
            + "|(?:\\bnot\\b|\\bno\\b|\\bwithout\\b|\\black\\b|\\blacks\\b"
            + "|\\bdoesn'?t\\b|\\bdon'?t\\b|\\bwon'?t\\b|\\bcan'?t\\b"
            + "|\\bnever\\b|\\bhardly\\b|\\bbarely\\b)",
            Pattern.CASE_INSENSITIVE);

    public VerificationResult check(String answer, List<ProjectRecommendation> recommendations) {
        VerificationResult result = new VerificationResult();

        if (answer == null || answer.isBlank()) {
            return result;
        }
        if (recommendations == null || recommendations.isEmpty()) {
            return result;
        }

        for (ProjectRecommendation rec : recommendations) {
            String repoName = rec.fullName();
            if (repoName == null || repoName.isBlank()) {
                continue;
            }

            String evidenceStr = String.join(" ", rec.score().evidence());

            String context = extractRepoSection(answer, repoName);
            if (context == null || context.isBlank()) {
                continue;
            }
            String contextLower = context.toLowerCase();

            checkDocClaims(result, contextLower, evidenceStr, repoName);
            checkExampleClaims(result, contextLower, evidenceStr, repoName);
            checkProductionClaims(result, contextLower, evidenceStr, repoName);
        }

        return result;
    }

    private void checkDocClaims(VerificationResult result, String contextLower, String evidence, String repoName) {
        boolean hasDocClaim = hasPositiveClaim(contextLower, DOCUMENTED_CLAIMS);
        boolean hasDocEvidence = DOC_DOCS_EVIDENCE.matcher(evidence).find();

        if (hasDocClaim && !hasDocEvidence) {
            result.setEvidenceClaimsOk(false);
            result.addIssue(VerificationIssue.warning(
                    "EVIDENCE_UNVERIFIED",
                    "回答声称 " + repoName + " 文档完善，但 evidence 中无对应的 docs 证据",
                    "repo=" + repoName + " evidence=" + VerifierUtils.truncate(evidence, 120)
            ));
        }
    }

    private void checkExampleClaims(VerificationResult result, String contextLower, String evidence, String repoName) {
        boolean hasExampleClaim = hasPositiveClaim(contextLower, EXAMPLE_CLAIMS);
        boolean hasExampleEvidence = LEARNING_EXAMPLE_EVIDENCE.matcher(evidence).find();

        if (hasExampleClaim && !hasExampleEvidence) {
            result.setEvidenceClaimsOk(false);
            result.addIssue(VerificationIssue.warning(
                    "EVIDENCE_UNVERIFIED",
                    "回答声称 " + repoName + " 有示例代码，但 evidence 中无对应的 learning/examples 证据",
                    "repo=" + repoName + " evidence=" + VerifierUtils.truncate(evidence, 120)
            ));
        }
    }

    private void checkProductionClaims(VerificationResult result, String contextLower, String evidence, String repoName) {
        boolean hasProductionClaim = hasPositiveClaim(contextLower, PRODUCTION_CLAIMS);
        boolean hasMatchEvidence = MATCH_EVIDENCE.matcher(evidence).find();

        if (hasProductionClaim && !hasMatchEvidence) {
            result.setEvidenceClaimsOk(false);
            result.addIssue(VerificationIssue.warning(
                    "EVIDENCE_UNVERIFIED",
                    "回答声称 " + repoName + " 为生产级/企业级，但 evidence 中缺少 match 证据支持",
                    "repo=" + repoName + " evidence=" + VerifierUtils.truncate(evidence, 120)
            ));
        }
    }

    private boolean hasPositiveClaim(String contextLower, Set<String> claims) {
        for (String claim : claims) {
            int idx = 0;
            while ((idx = contextLower.indexOf(claim, idx)) >= 0) {
                String before = contextLower.substring(Math.max(0, idx - 15), idx);
                if (!NEGATION_PATTERN.matcher(before).find()) {
                    return true;
                }
                idx += claim.length();
            }
        }
        return false;
    }

    private String extractRepoSection(String answer, String fullName) {
        int idx = answer.indexOf(fullName);
        int nameLen = fullName.length();
        if (idx < 0) {
            String shortName = VerifierUtils.extractShortName(fullName);
            if (!shortName.isEmpty()) {
                idx = answer.indexOf(shortName);
                nameLen = shortName.length();
            }
        }
        if (idx < 0) {
            return null;
        }
        int start = Math.max(0, idx - 50);
        int end = Math.min(answer.length(), idx + nameLen + 300);
        return answer.substring(start, end);
    }
}
