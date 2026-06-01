package com.openscout.agent.react;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.client.RepoSummary;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EvidenceGapDetector {

    private static final String FETCH_README_ACTION = "fetch_readme";

    public List<EvidenceGap> detect(List<RepoSummary> repos,
                                    List<ProjectRecommendation> recommendations,
                                    int maxGaps) {
        if (maxGaps <= 0 || recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        Map<String, RepoSummary> reposByFullName = (repos == null ? List.<RepoSummary>of() : repos).stream()
                .collect(Collectors.toMap(RepoSummary::fullName, Function.identity(), (left, right) -> left));
        return recommendations.stream()
                .limit(maxGaps)
                .map(recommendation -> toGap(recommendation, reposByFullName.get(recommendation.fullName())))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .sorted(Comparator.comparingInt(EvidenceGap::priority))
                .limit(maxGaps)
                .toList();
    }

    private java.util.Optional<EvidenceGap> toGap(ProjectRecommendation recommendation, RepoSummary repo) {
        if (repo == null) {
            return java.util.Optional.empty();
        }
        List<String> evidence = recommendation.score().evidence() == null
                ? List.of() : recommendation.score().evidence();
        boolean hasDocEvidence = evidence.stream().anyMatch(item -> item.startsWith("docs:"));
        boolean hasLongReadmeEvidence = evidence.stream().anyMatch(item -> item.contains("README length >= 2000"));

        if (repo.readmeLength() <= 0) {
            return java.util.Optional.of(new EvidenceGap(
                    recommendation.fullName(),
                    EvidenceGapType.MISSING_README,
                    "readmeLength=0 and no README evidence",
                    FETCH_README_ACTION,
                    1
            ));
        }
        if ("cache".equalsIgnoreCase(repo.source()) && !hasLongReadmeEvidence && !repo.hasExamples()) {
            return java.util.Optional.of(new EvidenceGap(
                    recommendation.fullName(),
                    EvidenceGapType.CACHE_EVIDENCE_INCOMPLETE,
                    "cache source has incomplete README evidence",
                    FETCH_README_ACTION,
                    2
            ));
        }
        if (!hasDocEvidence || recommendation.score().docScore() < 10) {
            return java.util.Optional.of(new EvidenceGap(
                    recommendation.fullName(),
                    EvidenceGapType.WEAK_DOC_EVIDENCE,
                    "documentation evidence is weak",
                    FETCH_README_ACTION,
                    3
            ));
        }
        return java.util.Optional.empty();
    }
}
