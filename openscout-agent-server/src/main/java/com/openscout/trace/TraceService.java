package com.openscout.trace;

import com.openscout.config.OpenScoutProperties;
import com.openscout.persistence.trace.TracePersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(token|api[-_]?key|authorization|password|secret)\\s*[:=]\\s*[^\\s,;]+");

    private final Map<String, AgentTrace> traces = new ConcurrentHashMap<>();
    private final OpenScoutProperties properties;
    private final TracePersistenceService tracePersistenceService;

    public TraceService(OpenScoutProperties properties, TracePersistenceService tracePersistenceService) {
        this.properties = properties;
        this.tracePersistenceService = tracePersistenceService;
    }

    public AgentTrace start(String question) {
        String traceId = UUID.randomUUID().toString();
        AgentTrace trace = new AgentTrace(traceId, sanitize(question), Instant.now());
        traces.put(traceId, trace);
        persistIfEnabled(() -> tracePersistenceService.insertTrace(trace));
        return trace;
    }

    public void recordToolCall(AgentTrace trace, String toolName, String inputSummary, String outputSummary, long latencyMs) {
        trace.getToolCalls().add(new TraceToolCall(
                toolName,
                sanitize(inputSummary),
                sanitize(outputSummary),
                latencyMs,
                "SUCCESS",
                null,
                Instant.now()
        ));
    }

    public void complete(AgentTrace trace, String scoreSummary, String finalAnswer) {
        trace.setScoreSummary(sanitize(scoreSummary));
        trace.setFinalAnswer(sanitize(finalAnswer));
        trace.setLatencyMs(Duration.between(trace.getStartedAt(), Instant.now()).toMillis());
        trace.setStatus("SUCCESS");
        persistIfEnabled(() -> tracePersistenceService.updateTrace(trace));
    }

    public void fail(AgentTrace trace, Throwable throwable) {
        trace.setErrorMessage(sanitize(throwable.getMessage()));
        trace.setLatencyMs(Duration.between(trace.getStartedAt(), Instant.now()).toMillis());
        trace.setStatus("FAILED");
        persistIfEnabled(() -> tracePersistenceService.updateTrace(trace));
    }

    public Optional<AgentTrace> find(String traceId) {
        Optional<AgentTrace> memory = Optional.ofNullable(traces.get(traceId));
        if (memory.isPresent()) {
            return memory;
        }
        if (properties.getPersistence().isEnabled()) {
            Optional<AgentTrace> fromDb = tracePersistenceService.findByTraceId(traceId);
            fromDb.ifPresent(trace -> traces.put(traceId, trace));
            return fromDb;
        }
        return Optional.empty();
    }

    private void persistIfEnabled(Runnable action) {
        if (properties.getPersistence().isEnabled()) {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("持久化写入失败，trace 仅在内存中可用：{}", e.getMessage());
            }
        }
    }

    public String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String redacted = SECRET_PATTERN.matcher(value).replaceAll("$1=<redacted>");
        int maxLength = properties.getTrace().getMaxSummaryLength();
        if (redacted.length() <= maxLength) {
            return redacted;
        }
        return redacted.substring(0, maxLength) + "...<truncated>";
    }
}
