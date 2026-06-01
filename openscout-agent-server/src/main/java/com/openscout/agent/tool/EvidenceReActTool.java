package com.openscout.agent.tool;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.react.EvidenceGap;
import com.openscout.agent.react.EvidenceGapDetector;
import com.openscout.agent.react.ReadmeEnrichmentResult;
import com.openscout.agent.react.ReadmeEvidenceEnricher;
import com.openscout.agent.recommendation.RecommendationScoringService;
import com.openscout.agent.runtime.AgentContext;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EvidenceReActTool implements AgentTool {

    public static final String NAME = "evidence_react";

    private final OpenScoutProperties properties;
    private final EvidenceGapDetector gapDetector;
    private final ReadmeEvidenceEnricher readmeEvidenceEnricher;
    private final RecommendationScoringService recommendationScoringService;
    private final TraceService traceService;

    public EvidenceReActTool(OpenScoutProperties properties,
                             EvidenceGapDetector gapDetector,
                             ReadmeEvidenceEnricher readmeEvidenceEnricher,
                             RecommendationScoringService recommendationScoringService,
                             TraceService traceService) {
        this.properties = properties;
        this.gapDetector = gapDetector;
        this.readmeEvidenceEnricher = readmeEvidenceEnricher;
        this.recommendationScoringService = recommendationScoringService;
        this.traceService = traceService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Instant start = Instant.now();
        OpenScoutProperties.React react = properties.getReact();
        int maxRounds = Math.max(0, react.getMaxRounds());
        int maxFollowUpRepos = Math.max(0, react.getMaxFollowUpRepos());

        if (!react.isEnabled() || maxRounds == 0 || maxFollowUpRepos == 0) {
            return stop(request, start, "disabled", "react disabled or limits are zero");
        }
        if (request.context().getMode() != AgentRuntimeMode.REAL) {
            return stop(request, start, "mode_not_real", "mode=" + request.context().getMode());
        }
        if (request.context().getRecommendations().isEmpty()) {
            return stop(request, start, "no_recommendations", "recommendations=0");
        }

        boolean changed = false;
        boolean rateLimited = false;
        int attempted = 0;
        for (int round = 1; round <= maxRounds; round++) {
            List<EvidenceGap> gaps = gapDetector.detect(
                    request.context().getRepos(),
                    request.context().getRecommendations(),
                    maxFollowUpRepos
            );
            recordGapDetected(request, round, gaps);
            if (gaps.isEmpty()) {
                return stop(request, start, "no_actionable_gap",
                        "round=" + round + " attempted=" + attempted);
            }

            Map<String, RepoSummary> reposByFullName = reposByFullName(request.context().getRepos());
            for (EvidenceGap gap : gaps) {
                RepoSummary repo = reposByFullName.get(gap.fullName());
                if (repo == null) {
                    recordObservation(request, gap, "missing_repo", "repo not found in context", 0, 0);
                    continue;
                }
                Optional<AgentContext.ReadmeFailureObservation> previousFailure =
                        request.context().readmeFailureObservation(gap.fullName());
                if (previousFailure.isPresent()) {
                    AgentContext.ReadmeFailureObservation observation = previousFailure.get();
                    recordObservation(request, gap, previousStatus(observation.status()),
                            "README already failed earlier in this ask: " + observation.status(),
                            repo.readmeLength(), observation.retryAfterSeconds());
                    if ("rate_limited".equals(observation.status())) {
                        rateLimited = true;
                        break;
                    }
                    continue;
                }
                attempted++;
                traceService.recordToolCall(request.trace(), "evidence_follow_up_started",
                        "repo=" + gap.fullName() + " gapType=" + gap.gapType(),
                        "action=" + gap.action() + " reason=" + gap.reason(),
                        0);
                ReadmeEnrichmentResult result = readmeEvidenceEnricher.enrichFromGitHub(repo);
                recordObservation(request, gap, result.status(), result.errorSummary(),
                        result.readmeLength(), result.retryAfterSeconds());
                if (result.rateLimited()) {
                    request.context().recordReadmeFailure(repo.fullName(), result.status(), result.retryAfterSeconds());
                    rateLimited = true;
                    break;
                }
                if (result.fetched()) {
                    request.context().clearReadmeFailure(repo.fullName());
                    replaceRepo(request.context(), result.repo());
                    changed = true;
                } else {
                    request.context().recordReadmeFailure(repo.fullName(), result.status(), result.retryAfterSeconds());
                }
            }
            if (changed) {
                rescore(request);
            }
            if (rateLimited) {
                return stop(request, start, "rate_limited",
                        "attempted=" + attempted + " changed=" + changed);
            }
        }

        List<EvidenceGap> remaining = gapDetector.detect(
                request.context().getRepos(),
                request.context().getRecommendations(),
                maxFollowUpRepos
        );
        if (remaining.isEmpty()) {
            return stop(request, start, "no_actionable_gap",
                    "attempted=" + attempted + " changed=" + changed);
        }
        return stop(request, start, "max_rounds_reached",
                "maxRounds=" + maxRounds + " remaining=" + remaining.size()
                        + " attempted=" + attempted + " changed=" + changed);
    }

    private void recordGapDetected(ToolRequest request, int round, List<EvidenceGap> gaps) {
        String repos = gaps.stream()
                .map(gap -> gap.fullName() + ":" + gap.gapType())
                .collect(Collectors.joining(","));
        traceService.recordToolCall(request.trace(), "evidence_gap_detected",
                "round=" + round,
                "gaps=" + gaps.size() + " repos=" + repos,
                0);
    }

    private void recordObservation(ToolRequest request, EvidenceGap gap, String status,
                                   String errorSummary, int readmeLength, int retryAfterSeconds) {
        String output = "status=" + status + " readmeLength=" + readmeLength;
        if (retryAfterSeconds > 0) {
            output += " retryAfterSeconds=" + retryAfterSeconds;
        }
        traceService.recordToolCall(request.trace(), "evidence_follow_up_observed",
                "repo=" + gap.fullName() + " gapType=" + gap.gapType(),
                output,
                0,
                "fetched".equals(status) ? "SUCCESS" : "FAILED",
                errorSummary);
    }

    private String previousStatus(String status) {
        if ("rate_limited".equals(status)) {
            return status;
        }
        return "skipped_previous_" + status;
    }

    private void rescore(ToolRequest request) {
        List<ProjectRecommendation> recommendations = recommendationScoringService.score(
                request.context().getUserGoal(), request.context().getRepos());
        request.context().setRecommendations(recommendations);
        recommendationScoringService.persistReposIfEnabled(request.context().getRepos(), recommendations,
                request.context().getUserGoal(), request.trace());
        traceService.recordToolCall(request.trace(), "evidence_rescore_completed",
                "repos=" + request.context().getRepos().size(),
                "recommendations=" + recommendations.size(),
                0);
    }

    private ToolResult stop(ToolRequest request, Instant start, String reason, String detail) {
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        traceService.recordToolCall(request.trace(), "evidence_react_stopped",
                "reason=" + reason,
                detail,
                latencyMs);
        return ToolResult.success("stopped reason=" + reason);
    }

    private Map<String, RepoSummary> reposByFullName(List<RepoSummary> repos) {
        return repos.stream()
                .collect(Collectors.toMap(RepoSummary::fullName, Function.identity(), (left, right) -> left));
    }

    private void replaceRepo(AgentContext context, RepoSummary updated) {
        List<RepoSummary> repos = new ArrayList<>(context.getRepos());
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).fullName().equals(updated.fullName())) {
                repos.set(i, updated);
                context.setRepos(repos);
                return;
            }
        }
    }
}
