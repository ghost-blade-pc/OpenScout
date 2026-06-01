package com.openscout.trace;

import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.agent.runtime.PlanStepStatus;
import com.openscout.agent.runtime.StepObservation;
import com.openscout.agent.event.AgentEventPublisher;
import com.openscout.config.OpenScoutProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceServiceTest {

    @Test
    void sanitizeShouldRedactSensitiveValuesAndTruncateLargeText() {
        OpenScoutProperties properties = new OpenScoutProperties();
        properties.getTrace().setMaxSummaryLength(40);
        TraceService traceService = new TraceService(properties, null);

        String sanitized = traceService.sanitize("Authorization: Bearer abc token=secret-value and a very long body");

        assertThat(sanitized).contains("<redacted>");
        assertThat(sanitized).doesNotContain("secret-value");
        assertThat(sanitized).endsWith("...<truncated>");
    }

    @Test
    void shouldRecordRuntimeObservationAsToolCallWithSanitization() {
        OpenScoutProperties properties = new OpenScoutProperties();
        TraceService traceService = new TraceService(properties, null);
        AgentTrace trace = traceService.start("question");

        traceService.recordObservation(trace, new StepObservation(
                "step-1",
                "search_repos",
                PlanStepStatus.FAILED,
                "mode=" + AgentRuntimeMode.MOCK,
                "api_key=secret-value",
                12
        ));

        TraceToolCall event = trace.getToolCalls().get(0);
        assertThat(event.toolName()).isEqualTo("agent_observation_created");
        assertThat(event.status()).isEqualTo("FAILED");
        assertThat(event.outputSummary()).contains("MOCK");
        assertThat(event.errorMessage()).contains("<redacted>");
        assertThat(event.errorMessage()).doesNotContain("secret-value");
    }

    @Test
    void shouldPublishSanitizedTraceEventWhenRunRegistered() {
        OpenScoutProperties properties = new OpenScoutProperties();
        AgentEventPublisher publisher = new AgentEventPublisher(properties);
        TraceService traceService = new TraceService(properties, null, publisher);
        AgentTrace trace = traceService.start("question");
        publisher.registerRun("run-1", trace.getTraceId());

        traceService.recordObservation(trace, new StepObservation(
                "step-1",
                "search_repos",
                PlanStepStatus.FAILED,
                "summary",
                "api_key=secret-value",
                12
        ));

        assertThat(publisher.bufferedEvents("run-1"))
                .anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo("observation_created");
                    assertThat(event.errorSummary()).contains("<redacted>");
                    assertThat(event.errorSummary()).doesNotContain("secret-value");
                });
    }
}
