package com.openscout.scoring;

import com.openscout.client.RepoSummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectScoreService {

    private static final int MAX_TOTAL = 100;

    public ProjectScore score(String goal, RepoSummary repo) {
        ArrayList<String> evidence = new ArrayList<>();
        int activityScore = activityScore(repo, evidence);
        int docScore = docScore(repo, evidence);
        int matchScore = matchScore(goal, repo, evidence);
        int learningScore = learningScore(repo, evidence);
        int resumeValueScore = resumeValueScore(repo, evidence);
        int total = Math.min(MAX_TOTAL, activityScore + docScore + matchScore + learningScore + resumeValueScore);
        return new ProjectScore(total, activityScore, docScore, matchScore, learningScore, resumeValueScore, evidence);
    }

    private int activityScore(RepoSummary repo, ArrayList<String> evidence) {
        Instant updatedAt = repo.pushedAt() != null ? repo.pushedAt() : repo.updatedAt();
        if (updatedAt == null) {
            evidence.add("activity: missing update timestamp");
            return 5;
        }
        long days = Duration.between(updatedAt, Instant.now()).toDays();
        if (days <= 180) {
            evidence.add("activity: updated within 180 days");
            return 20;
        }
        if (days <= 365) {
            evidence.add("activity: updated within 365 days");
            return 12;
        }
        evidence.add("activity: stale for more than 365 days");
        return 4;
    }

    private int docScore(RepoSummary repo, ArrayList<String> evidence) {
        int score = 0;
        if (repo.readmeLength() >= 2000) {
            score += 12;
            evidence.add("docs: README length >= 2000");
        } else if (repo.readmeLength() > 0) {
            score += 6;
            evidence.add("docs: README exists but is short");
        }
        if (repo.hasDocker()) {
            score += 4;
            evidence.add("docs: Docker artifact found");
        }
        return Math.min(20, score);
    }

    private int matchScore(String goal, RepoSummary repo, ArrayList<String> evidence) {
        Set<String> keywords = keywords(goal);
        int score = 0;
        String language = lower(repo.language());
        if (!language.isBlank() && keywords.contains(language)) {
            score += 12;
            evidence.add("match: language matches goal");
        }
        String text = lower(repo.fullName() + " " + repo.description() + " " + String.join(" ", repo.topics() == null ? java.util.List.of() : repo.topics()));
        long hits = keywords.stream().filter(text::contains).count();
        if (hits > 0) {
            score += (int) Math.min(18, hits * 6);
            evidence.add("match: matched " + hits + " goal keyword(s)");
        }
        return Math.min(30, score);
    }

    private int learningScore(RepoSummary repo, ArrayList<String> evidence) {
        int score = 6;
        if (repo.hasExamples()) {
            score += 8;
            evidence.add("learning: examples directory found");
        }
        if (repo.readmeLength() >= 1000) {
            score += 6;
            evidence.add("learning: README has enough onboarding material");
        }
        return Math.min(20, score);
    }

    private int resumeValueScore(RepoSummary repo, ArrayList<String> evidence) {
        int score;
        if (repo.stars() >= 10_000) {
            score = 10;
            evidence.add("resume: stars >= 10000");
        } else if (repo.stars() >= 1_000) {
            score = 8;
            evidence.add("resume: stars >= 1000");
        } else if (repo.stars() >= 100) {
            score = 5;
            evidence.add("resume: stars >= 100");
        } else {
            score = 2;
            evidence.add("resume: low star count");
        }
        if (repo.openIssues() > 500) {
            score = Math.max(0, score - 2);
            evidence.add("resume: many open issues, slight penalty");
        }
        return score;
    }

    private Set<String> keywords(String goal) {
        return Arrays.stream(lower(goal).split("[^a-z0-9]+"))
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toSet());
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
