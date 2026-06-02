package com.openscout.agent.verifier;

import com.openscout.agent.ProjectRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScoreIntegrityChecker {

    private static final Logger log = LoggerFactory.getLogger(ScoreIntegrityChecker.class);

    private static final Pattern SCORE_PATTERN = Pattern.compile(
            "(?:评分|总分|得分|分数|综合评分|total\\s?score)\\s*(?:[：:是为]\\s*)?(\\d{1,3})\\s*(?:分|/\\s*100)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern SCORE_NUMBER_PATTERN = Pattern.compile(
            "(?<![a-zA-Z])" +
            "(\\d{1,3})\\s*(?:分|/\\s*100)" +
            "(?![a-zA-Z])",
            Pattern.CASE_INSENSITIVE
    );

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
            int expectedScore = rec.score().totalScore();

            if (repoName == null || repoName.isBlank()) {
                continue;
            }

            Integer extractedScore = extractScoreNearRepo(answer, repoName);

            if (extractedScore == null) {
                // 项目名未出现在回答中 → 跳过（LLM 可能仅覆盖 Top N 项目）
                if (!repoMentionedInAnswer(answer, repoName)) {
                    continue;
                }
                result.setScoreIntegrityOk(false);
                result.addIssue(VerificationIssue.warning(
                        "SCORE_FORMAT_UNRECOGNIZED",
                        "无法从回答中提取 " + repoName + " 的分数数字",
                        "repo=" + repoName + " expected=" + expectedScore
                ));
                continue;
            }

            if (extractedScore != expectedScore) {
                result.setScoreIntegrityOk(false);
                result.addIssue(VerificationIssue.error(
                        "SCORE_TAMPERING",
                        "回答中 " + repoName + " 的分数 " + extractedScore + " 与规则评分 " + expectedScore + " 不一致",
                        "repo=" + repoName + " expected=" + expectedScore + " found=" + extractedScore
                ));
            }
        }

        return result;
    }

    /**
     * 检查项目名是否在回答文本中出现（全名或短名）。
     */
    private boolean repoMentionedInAnswer(String answer, String fullName) {
        if (answer.contains(fullName)) {
            return true;
        }
        String shortName = VerifierUtils.extractShortName(fullName);
        return !shortName.isEmpty() && answer.contains(shortName);
    }

    Integer extractScoreNearRepo(String answer, String fullName) {
        int repoIdx = answer.indexOf(fullName);
        int nameLen = fullName.length();
        if (repoIdx < 0) {
            String shortName = VerifierUtils.extractShortName(fullName);
            if (!shortName.isEmpty()) {
                repoIdx = answer.indexOf(shortName);
                nameLen = shortName.length();
            }
        }
        if (repoIdx < 0) {
            return null;
        }
        int contextStart = Math.max(0, repoIdx - 50);
        int contextEnd = Math.min(answer.length(), repoIdx + nameLen + 200);
        String context = answer.substring(contextStart, contextEnd);

        Matcher scoreMatcher = SCORE_PATTERN.matcher(context);
        if (scoreMatcher.find()) {
            try {
                return Integer.parseInt(scoreMatcher.group(1));
            } catch (NumberFormatException e) {
                log.debug("无法解析提取的分数: {}", scoreMatcher.group(1));
            }
        }
        scoreMatcher = SCORE_NUMBER_PATTERN.matcher(context);
        if (scoreMatcher.find()) {
            try {
                return Integer.parseInt(scoreMatcher.group(1));
            } catch (NumberFormatException e) {
                log.debug("无法解析提取的分数: {}", scoreMatcher.group(1));
            }
        }
        return null;
    }
}
