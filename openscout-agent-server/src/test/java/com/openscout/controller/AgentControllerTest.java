package com.openscout.controller;

import com.openscout.agent.AgentAskRequest;
import com.openscout.agent.AgentService;
import com.openscout.agent.run.AgentRunCreateResponse;
import com.openscout.agent.run.AgentRunResponse;
import com.openscout.agent.run.AgentRunService;
import com.openscout.agent.run.AgentRunStatus;
import com.openscout.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    private final AgentService agentService = mock(AgentService.class);
    private final TraceService traceService = mock(TraceService.class);
    private final AgentRunService agentRunService = mock(AgentRunService.class);
    private final AgentController controller = new AgentController(agentService, traceService, agentRunService);

    @Test
    void shouldCreateRun() {
        AgentAskRequest request = new AgentAskRequest("learn Java", null, null);
        AgentRunCreateResponse created = new AgentRunCreateResponse(
                "run-1", "trace-1", AgentRunStatus.RUNNING, "/api/agent/runs/run-1/events");
        when(agentRunService.createRun(request)).thenReturn(created);

        AgentRunCreateResponse response = controller.createRun(request);

        assertThat(response).isEqualTo(created);
    }

    @Test
    void shouldReturnRunWhenFound() {
        AgentRunResponse run = new AgentRunResponse("run-1", "trace-1", AgentRunStatus.SUCCEEDED,
                "answer", List.of(), null, null, 12, Instant.now(), Instant.now(),
                "/api/agent/runs/run-1/events");
        when(agentRunService.findRun("run-1")).thenReturn(Optional.of(run));

        var response = controller.getRun("run-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(run);
    }

    @Test
    void shouldReturnNotFoundWhenRunMissing() {
        when(agentRunService.findRun("missing")).thenReturn(Optional.empty());

        var response = controller.getRun("missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void shouldReturnSseEmitterWhenRunExists() {
        SseEmitter emitter = new SseEmitter();
        when(agentRunService.subscribe("run-1")).thenReturn(Optional.of(emitter));

        var response = controller.streamEvents("run-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isSameAs(emitter);
    }

    @Test
    void shouldReturnUnavailableForDisabledEvents() {
        var response = controller.handleServiceUnavailable(new IllegalStateException("Agent events stream is disabled"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().message()).contains("disabled");
    }
}
