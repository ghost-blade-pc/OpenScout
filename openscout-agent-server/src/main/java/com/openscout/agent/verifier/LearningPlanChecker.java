package com.openscout.agent.verifier;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.learning.LearningPlanResponse;
import com.openscout.learning.LearningTaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LearningPlanChecker {

    private static final Logger log = LoggerFactory.getLogger(LearningPlanChecker.class);

    private static final Pattern REPO_PATTERN = Pattern.compile(
            "([a-zA-Z0-9][\\w.-]+/[a-zA-Z0-9](?:[\\w.-]*[a-zA-Z0-9])?)"
    );

    private static final Pattern CANDIDATE_WORD_PATTERN = Pattern.compile(
            "\\b([a-zA-Z][a-zA-Z0-9._-]{2,})\\b"
    );

    private static final Set<String> COMMON_COMPOUND_WORDS = Set.of(
            "well-known", "state-of-the-art", "high-quality", "low-level", "high-level",
            "real-time", "open-source", "end-to-end", "out-of-the-box", "built-in",
            "all-in-one", "ready-to-use", "easy-to-use", "full-stack", "non-trivial",
            "best-practice", "best-practices", "cross-platform", "multi-thread",
            "single-page", "server-side", "client-side", "user-friendly",
            "long-term", "short-term", "third-party", "hard-coded"
    );

    public VerificationResult check(LearningPlanResponse learningPlan, List<ProjectRecommendation> recommendations) {
        VerificationResult result = new VerificationResult();

        if (learningPlan == null || learningPlan.tasks() == null || learningPlan.tasks().isEmpty()) {
            return result;
        }
        if (recommendations == null || recommendations.isEmpty()) {
            return result;
        }

        Set<String> validRepoNames = recommendations.stream()
                .map(ProjectRecommendation::fullName)
                .filter(n -> n != null)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Set<String> validShortNames = recommendations.stream()
                .map(ProjectRecommendation::fullName)
                .filter(n -> n != null)
                .map(VerifierUtils::extractShortName)
                .filter(n -> !n.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        for (LearningTaskResponse task : learningPlan.tasks()) {
            if (task == null) {
                continue;
            }
            checkTaskRepoReferences(result, task, validRepoNames, validShortNames);
        }

        return result;
    }

    private void checkTaskRepoReferences(VerificationResult result, LearningTaskResponse task,
                                         Set<String> validRepoNames, Set<String> validShortNames) {
        String detail = task.detail() != null ? task.detail() : "";
        String title = task.title() != null ? task.title() : "";
        String combined = title + " " + detail;

        Matcher matcher = REPO_PATTERN.matcher(combined);
        while (matcher.find()) {
            String refRepo = matcher.group(1).toLowerCase();
            if (!validRepoNames.contains(refRepo) && !validShortNames.contains(refRepo)) {
                boolean isPartialMatch = validShortNames.stream().anyMatch(refRepo::contains)
                        || validRepoNames.stream().anyMatch(r -> r.contains(refRepo));
                if (!isPartialMatch) {
                    result.setLearningPlanOk(false);
                    result.addIssue(VerificationIssue.warning(
                            "PLAN_HALLUCINATION",
                            "学习任务 Day" + task.dayNo() + " 引用了不在推荐列表中的仓库: " + refRepo,
                            "task=" + task.title() + " ref=" + refRepo
                    ));
                }
            }
        }

        checkShortNameReferences(result, task, combined, validShortNames);
    }

    private void checkShortNameReferences(VerificationResult result, LearningTaskResponse task,
                                          String combined, Set<String> validShortNames) {
        for (String shortName : extractPotentialRepoRefs(combined)) {
            if (shortName.length() < 3) {
                continue;
            }
            if (validShortNames.contains(shortName.toLowerCase())) {
                continue;
            }
            result.setLearningPlanOk(false);
            result.addIssue(VerificationIssue.warning(
                    "PLAN_HALLUCINATION",
                    "学习任务 Day" + task.dayNo() + " 可能引用了不在推荐列表中的仓库简称: " + shortName,
                    "task=" + task.title() + " shortRef=" + shortName
            ));
        }
    }

    private Set<String> extractPotentialRepoRefs(String text) {
        Matcher m = CANDIDATE_WORD_PATTERN.matcher(text);
        java.util.Set<String> refs = new java.util.HashSet<>();
        while (m.find()) {
            String word = m.group(1);
            if (!word.contains("-") && !word.contains(".") && !word.contains("_")) {
                continue;
            }
            if (COMMON_COMPOUND_WORDS.contains(word.toLowerCase())) {
                continue;
            }
            if (isOwnerOfValidRepo(text, word)) {
                continue;
            }
            refs.add(word);
        }
        return refs;
    }

    private boolean isOwnerOfValidRepo(String text, String word) {
        int idx = 0;
        while ((idx = text.indexOf(word, idx)) >= 0) {
            int afterWord = idx + word.length();
            if (afterWord < text.length() && text.charAt(afterWord) == '/') {
                return true;
            }
            idx++;
        }
        return false;
    }
}
