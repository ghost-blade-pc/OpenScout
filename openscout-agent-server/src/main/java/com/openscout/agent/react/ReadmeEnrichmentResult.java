package com.openscout.agent.react;

import com.openscout.client.RepoSummary;

public record ReadmeEnrichmentResult(
        RepoSummary repo,
        boolean fetched,
        boolean rateLimited,
        String status,
        String errorSummary,
        int retryAfterSeconds,
        int readmeLength
) {

    public static ReadmeEnrichmentResult fetched(RepoSummary repo, int readmeLength) {
        return new ReadmeEnrichmentResult(repo, true, false, "fetched", null, 0, readmeLength);
    }

    public static ReadmeEnrichmentResult skipped(RepoSummary repo, String status, String errorSummary) {
        return new ReadmeEnrichmentResult(repo, false, false, status, errorSummary, 0, repo.readmeLength());
    }

    public static ReadmeEnrichmentResult rateLimited(RepoSummary repo, String errorSummary, int retryAfterSeconds) {
        return new ReadmeEnrichmentResult(repo, false, true, "rate_limited", errorSummary,
                retryAfterSeconds, repo.readmeLength());
    }
}
