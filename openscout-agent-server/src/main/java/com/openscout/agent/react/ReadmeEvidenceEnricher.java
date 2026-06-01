package com.openscout.agent.react;

import com.openscout.client.CollectorClient;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RateLimitException;
import com.openscout.client.ReadmeResponse;
import com.openscout.client.RepoSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class ReadmeEvidenceEnricher {

    private static final Logger log = LoggerFactory.getLogger(ReadmeEvidenceEnricher.class);
    private static final Pattern EXAMPLES_PATTERN = Pattern.compile(
            "(?i)\\b(example[s]?|sample[s]?|demo[s]?|tutorial[s]?|quickstart)\\b");

    private final CollectorClient collectorClient;

    public ReadmeEvidenceEnricher(CollectorClient collectorClient) {
        this.collectorClient = collectorClient;
    }

    public ReadmeEnrichmentResult enrichFromGitHub(RepoSummary repo) {
        try {
            ReadmeResponse readme = collectorClient.getReadme(repo.owner(), repo.repo(), "github");
            String readmeText = readme.readme() != null ? readme.readme() : "";
            int readmeLength = Math.max(readme.length(), readmeText.length());
            if (readmeLength <= 0) {
                return ReadmeEnrichmentResult.skipped(repo, "empty_readme", "README is empty");
            }
            boolean hasExamples = containsExamples(readmeText);
            boolean hasDocker = hasDockerTopic(repo);
            RepoSummary enriched = new RepoSummary(
                    repo.owner(), repo.repo(), repo.fullName(), repo.description(),
                    repo.language(), repo.stars(), repo.forks(), repo.topics(),
                    repo.license(), repo.openIssues(), repo.updatedAt(), repo.pushedAt(),
                    readmeLength, hasExamples, hasDocker, repo.source()
            );
            return ReadmeEnrichmentResult.fetched(enriched, readmeLength);
        } catch (GitHubApiException e) {
            String status = e.getHttpStatus() == 404 || "NOT_FOUND".equals(e.getErrorCode())
                    ? "not_found" : "github_error";
            log.warn("README fetch skipped for {}: code={} status={}",
                    repo.fullName(), e.getErrorCode(), e.getHttpStatus());
            return ReadmeEnrichmentResult.skipped(repo, status,
                    "code=" + e.getErrorCode() + " status=" + e.getHttpStatus() + " message=" + e.getMessage());
        } catch (RateLimitException e) {
            log.warn("README fetch skipped for {} due to rate limit (retry after {}s)",
                    repo.fullName(), e.getRetryAfterSeconds());
            return ReadmeEnrichmentResult.rateLimited(repo,
                    "retryAfterSeconds=" + e.getRetryAfterSeconds() + " message=" + e.getMessage(),
                    e.getRetryAfterSeconds());
        } catch (Exception e) {
            log.warn("README fetch failed for {}, skipping enrichment: {}", repo.fullName(), e.getMessage());
            return ReadmeEnrichmentResult.skipped(repo, "failed", e.getMessage());
        }
    }

    public static boolean containsExamples(String readmeText) {
        if (readmeText == null || readmeText.isBlank()) {
            return false;
        }
        return EXAMPLES_PATTERN.matcher(readmeText.substring(0, Math.min(2000, readmeText.length()))).find();
    }

    public static int inferReadmeLength(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0;
        }
        for (String e : evidence) {
            if (e.contains("README length >= 2000")) {
                return 2000;
            }
            if (e.contains("README exists but is short")) {
                return 500;
            }
        }
        return 0;
    }

    public static boolean hasExamplesEvidence(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        return evidence.stream().anyMatch(item -> containsExamples(item));
    }

    public static boolean hasDockerTopic(RepoSummary repo) {
        return repo.topics() != null && repo.topics().stream()
                .anyMatch(t -> "docker".equalsIgnoreCase(t));
    }
}
