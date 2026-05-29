package com.openscout.trace;

import com.openscout.config.OpenScoutProperties;
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

    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(token|api[-_]?key|authorization|password|secret)\\s*[:=]\\s*[^\\s,;]+");

    private final Map<String, AgentTrace> traces = new ConcurrentHashMap<>();
    private final OpenScoutProperties properties;

    public TraceService(OpenScoutProperties properties) {
        this.properties = properties;
    }

    public AgentTrace start(String question) {
        String traceId = UUID.randomUUID().toString();
        AgentTrace trace = new AgentTrace(traceId, sanitize(question), Instant.now());
        traces.put(traceId, trace);
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
    }

    public void fail(AgentTrace trace, Throwable throwable) {
        trace.setErrorMessage(sanitize(throwable.getMessage()));
        trace.setLatencyMs(Duration.between(trace.getStartedAt(), Instant.now()).toMillis());
        trace.setStatus("FAILED");
    }

    public Optional<AgentTrace> find(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
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
