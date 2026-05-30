package com.openscout.controller;

import com.openscout.agent.AgentAskRequest;
import com.openscout.agent.AgentAskResponse;
import com.openscout.agent.AgentCallException;
import com.openscout.agent.AgentErrorResponse;
import com.openscout.agent.MockAgentService;
import com.openscout.client.RateLimitException;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final MockAgentService mockAgentService;
    private final TraceService traceService;

    public AgentController(MockAgentService mockAgentService, TraceService traceService) {
        this.mockAgentService = mockAgentService;
        this.traceService = traceService;
    }

    @PostMapping("/ask")
    public AgentAskResponse ask(@RequestBody AgentAskRequest request) {
        return mockAgentService.ask(request);
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<AgentTrace> getTrace(@PathVariable String traceId) {
        return traceService.find(traceId)
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
}
