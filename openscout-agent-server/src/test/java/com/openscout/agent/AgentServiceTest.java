package com.openscout.agent;

import com.openscout.agent.runtime.AgentRuntimeResult;
import com.openscout.agent.runtime.PlanExecutor;
import com.openscout.config.OpenScoutProperties;
import com.openscout.scoring.ProjectScore;
import com.openscout.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    @Test
    void shouldKeepAskResponseCompatible() {
        TraceService traceService = new TraceService(new OpenScoutProperties(), null);
        PlanExecutor planExecutor = mock(PlanExecutor.class);
        ProjectRecommendation recommendation = recommendation();
        when(planExecutor.execute(eq("learn Java"), any()))
                .thenReturn(new AgentRuntimeResult("answer", List.of(recommendation), null,
                        "example/repo=80"));
        AgentService service = new AgentService(traceService, planExecutor);

        AgentAskResponse response = service.ask(new AgentAskRequest("learn Java", null, null));

        assertThat(response.traceId()).isNotBlank();
        assertThat(response.answer()).isEqualTo("answer");
        assertThat(response.recommendations()).containsExactly(recommendation);
        assertThat(response.learningPlan()).isNull();
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    private ProjectRecommendation recommendation() {
        return new ProjectRecommendation(
                "example/repo",
                "desc",
                "Java",
                100,
                "2026-05-31T00:00:00Z",
                new ProjectScore(80, 20, 20, 20, 10, 10, List.of("evidence")),
                "reason"
        );
    }
}
