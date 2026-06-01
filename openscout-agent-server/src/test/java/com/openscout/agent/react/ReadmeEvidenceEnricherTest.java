package com.openscout.agent.react;

import com.openscout.client.CollectorClient;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RateLimitException;
import com.openscout.client.ReadmeResponse;
import com.openscout.client.RepoSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadmeEvidenceEnricherTest {

    private final CollectorClient collectorClient = mock(CollectorClient.class);
    private final ReadmeEvidenceEnricher enricher = new ReadmeEvidenceEnricher(collectorClient);

    @Test
    void shouldEnrichRepoFromReadme() {
        RepoSummary repo = repo("example", "repo");
        when(collectorClient.getReadme("example", "repo", "github"))
                .thenReturn(new ReadmeResponse("example/repo",
                        "# Quickstart\n\nSee examples and tutorials.", 2400, "github"));

        ReadmeEnrichmentResult result = enricher.enrichFromGitHub(repo);

        assertThat(result.fetched()).isTrue();
        assertThat(result.repo().readmeLength()).isEqualTo(2400);
        assertThat(result.repo().hasExamples()).isTrue();
    }

    @Test
    void shouldReturnNotFoundObservation() {
        RepoSummary repo = repo("example", "missing");
        when(collectorClient.getReadme("example", "missing", "github"))
                .thenThrow(new GitHubApiException(null, "not found", 404, "NOT_FOUND"));

        ReadmeEnrichmentResult result = enricher.enrichFromGitHub(repo);

        assertThat(result.fetched()).isFalse();
        assertThat(result.status()).isEqualTo("not_found");
        assertThat(result.errorSummary()).contains("NOT_FOUND");
    }

    @Test
    void shouldSkipEmptyReadme() {
        RepoSummary repo = repo("example", "empty");
        when(collectorClient.getReadme("example", "empty", "github"))
                .thenReturn(new ReadmeResponse("example/empty", "", 0, "github"));

        ReadmeEnrichmentResult result = enricher.enrichFromGitHub(repo);

        assertThat(result.fetched()).isFalse();
        assertThat(result.status()).isEqualTo("empty_readme");
        assertThat(result.readmeLength()).isZero();
    }

    @Test
    void shouldReturnRateLimitObservation() {
        RepoSummary repo = repo("example", "limited");
        when(collectorClient.getReadme("example", "limited", "github"))
                .thenThrow(new RateLimitException(null, "rate limited", 60));

        ReadmeEnrichmentResult result = enricher.enrichFromGitHub(repo);

        assertThat(result.rateLimited()).isTrue();
        assertThat(result.status()).isEqualTo("rate_limited");
        assertThat(result.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void shouldInferReadmeLengthFromEvidence() {
        assertThat(ReadmeEvidenceEnricher.inferReadmeLength(List.of("docs: README length >= 2000")))
                .isEqualTo(2000);
        assertThat(ReadmeEvidenceEnricher.inferReadmeLength(List.of("docs: README exists but is short")))
                .isEqualTo(500);
    }

    private RepoSummary repo(String owner, String name) {
        return new RepoSummary(
                owner,
                name,
                owner + "/" + name,
                "A Java learning project",
                "Java",
                1000,
                100,
                List.of("java"),
                "Apache-2.0",
                10,
                Instant.now(),
                Instant.now(),
                0,
                false,
                false,
                "github"
        );
    }
}
