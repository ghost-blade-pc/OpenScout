package com.openscout.agent.tool;

import com.openscout.agent.react.ReadmeEnrichmentResult;
import com.openscout.agent.react.ReadmeEvidenceEnricher;
import com.openscout.agent.runtime.AgentContext;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.agent.runtime.PlanStep;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.memory.ProjectMemoryService;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FetchReadmeToolTest {

    @Test
    void shouldStopReadmeFetchWhenRateLimitedAndKeepRemainingRepos() {
        TraceService traceService = new TraceService(new OpenScoutProperties(), null);
        ProjectMemoryService memoryService = mock(ProjectMemoryService.class);
        ReadmeEvidenceEnricher enricher = mock(ReadmeEvidenceEnricher.class);
        FetchReadmeTool tool = new FetchReadmeTool(traceService, memoryService, enricher);
        RepoSummary limited = repo("example", "limited");
        RepoSummary skipped = repo("example", "skipped");
        RepoSummary kept = repo("example", "kept");
        when(memoryService.isEnabled()).thenReturn(false);
        when(enricher.enrichFromGitHub(limited))
                .thenReturn(ReadmeEnrichmentResult.rateLimited(limited, "retryAfterSeconds=60", 60));
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.REAL);
        context.setRepos(List.of(limited, skipped, kept));
        AgentTrace trace = traceService.start("learn Java");

        ToolResult result = tool.execute(new ToolRequest(
                new PlanStep("step-4", FetchReadmeTool.NAME, "purpose", "input", true),
                context,
                trace
        ));

        assertThat(result.outputSummary()).contains("targets=3", "skipped=1");
        verify(enricher, times(1)).enrichFromGitHub(limited);
        assertThat(context.getRepos()).extracting(RepoSummary::fullName)
                .containsExactly("example/limited", "example/skipped", "example/kept");
        assertThat(context.readmeFailureObservation("example/limited"))
                .hasValueSatisfying(observation -> {
                    assertThat(observation.status()).isEqualTo("rate_limited");
                    assertThat(observation.retryAfterSeconds()).isEqualTo(60);
                });
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
                List.of("java", "spring"),
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
