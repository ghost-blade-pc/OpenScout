package com.openscout.client;

import java.time.Instant;
import java.util.List;

public record RepoSummary(
        String owner,
        String repo,
        String fullName,
        String description,
        String language,
        long stars,
        long forks,
        List<String> topics,
        String license,
        long openIssues,
        Instant updatedAt,
        Instant pushedAt,
        int readmeLength,
        boolean hasExamples,
        boolean hasDocker,
        String source
) {
}
