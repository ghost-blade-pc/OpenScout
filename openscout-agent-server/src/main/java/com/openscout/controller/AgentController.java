package com.openscout.controller;

import com.openscout.agent.AgentAskRequest;
import com.openscout.agent.AgentAskResponse;
import com.openscout.agent.AgentCallException;
import com.openscout.agent.AgentErrorResponse;
import com.openscout.agent.AgentService;
import com.openscout.agent.run.AgentRunCreateResponse;
import com.openscout.agent.run.AgentRunResponse;
import com.openscout.agent.run.AgentRunService;
import com.openscout.client.RateLimitException;
import com.openscout.config.ApiKeyFilter;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final TraceService traceService;
    private final AgentRunService agentRunService;

    public AgentController(AgentService agentService, TraceService traceService, AgentRunService agentRunService) {
        this.agentService = agentService;
        this.traceService = traceService;
        this.agentRunService = agentRunService;
    }

    @PostMapping("/ask")
    public AgentAskResponse ask(@RequestBody AgentAskRequest request,
                                 HttpServletRequest httpRequest) {
        Long userId = extractUserId(httpRequest);
        return agentService.ask(request, userId);
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<AgentTrace> getTrace(@PathVariable String traceId) {
        return traceService.find(traceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/runs")
    public AgentRunCreateResponse createRun(@RequestBody AgentAskRequest request,
                                             HttpServletRequest httpRequest) {
        Long userId = extractUserId(httpRequest);
        return agentRunService.createRun(request, userId);
    }

    private Long extractUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(ApiKeyFilter.USER_ID_ATTR);
        return attr instanceof Long id ? id : null;
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<AgentRunResponse> getRun(@PathVariable String runId) {
        return agentRunService.findRun(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamEvents(@PathVariable String runId) {
        return agentRunService.subscribe(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new AgentErrorResponse(null, ex.getMessage()));
    }

    @ExceptionHandler(AgentCallException.class)
    public ResponseEntity<AgentErrorResponse> handleAgentCallException(AgentCallException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.BAD_GATEWAY);
        if (ex.getCause() instanceof RateLimitException rateLimitEx && rateLimitEx.getRetryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(rateLimitEx.getRetryAfterSeconds()));
        }
        return builder.body(new AgentErrorResponse(ex.getTraceId(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AgentErrorResponse> handleServiceUnavailable(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new AgentErrorResponse(null, ex.getMessage()));
    }
}
