package com.openscout.agent.event;

import com.openscout.config.OpenScoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AgentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentEventPublisher.class);

    private final OpenScoutProperties properties;
    private final Map<String, String> traceToRun = new ConcurrentHashMap<>();
    private final Map<String, Deque<AgentEvent>> buffers = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public AgentEventPublisher(OpenScoutProperties properties) {
        this.properties = properties;
    }

    public void registerRun(String runId, String traceId) {
        if (!properties.getEvents().isEnabled()) {
            return;
        }
        traceToRun.put(traceId, runId);
        buffers.computeIfAbsent(runId, ignored -> new ArrayDeque<>());
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
    }

    public SseEmitter subscribe(String runId) {
        if (!properties.getEvents().isEnabled()) {
            throw new IllegalStateException("Agent events stream is disabled");
        }
        SseEmitter emitter = new SseEmitter((long) properties.getEvents().getSseTimeoutSeconds() * 1000L);
        emitter.onCompletion(() -> removeSubscriber(runId, emitter));
        emitter.onTimeout(() -> removeSubscriber(runId, emitter));
        emitter.onError(ignored -> removeSubscriber(runId, emitter));
        List<AgentEvent> replay = bufferedEvents(runId);
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        for (AgentEvent event : replay) {
            send(runId, emitter, event);
        }
        if (!replay.isEmpty() && isTerminal(replay.get(replay.size() - 1))) {
            emitter.complete();
            removeSubscriber(runId, emitter);
        }
        return emitter;
    }

    public void publishRunStarted(String runId, String traceId, String status) {
        publish(new AgentEvent(runId, traceId, "run_started", status,
                null, null, null, "run started", null, 0, Instant.now()));
    }

    public void publishRunCompleted(String traceId, String status, long latencyMs) {
        publishForTrace(traceId, "run_completed", status, null, "run completed", null, latencyMs);
        completeSubscribers(traceToRun.get(traceId));
    }

    public void publishRunFailed(String traceId, String status, String errorSummary, long latencyMs) {
        publishForTrace(traceId, "run_failed", status, null, null, errorSummary, latencyMs);
        completeSubscribers(traceToRun.get(traceId));
    }

    public void publishTraceEvent(String traceId,
                                  String toolName,
                                  String inputSummary,
                                  String outputSummary,
                                  long latencyMs,
                                  String status,
                                  String errorSummary) {
        String eventType = toEventType(toolName);
        publishForTrace(traceId, eventType, status, toolName, inputSummary, outputSummary, errorSummary, latencyMs);
    }

    public void publishForTrace(String traceId,
                                String type,
                                String status,
                                String toolName,
                                String outputSummary,
                                String errorSummary,
                                long latencyMs) {
        publishForTrace(traceId, type, status, toolName, null, outputSummary, errorSummary, latencyMs);
    }

    public void publishForTrace(String traceId,
                                String type,
                                String status,
                                String toolName,
                                String inputSummary,
                                String outputSummary,
                                String errorSummary,
                                long latencyMs) {
        Optional.ofNullable(traceToRun.get(traceId))
                .ifPresent(runId -> publish(new AgentEvent(runId, traceId, type, status,
                        tryExtractStepId(inputSummary), toolName, inputSummary, outputSummary, errorSummary,
                        latencyMs, Instant.now())));
    }

    public List<AgentEvent> bufferedEvents(String runId) {
        Deque<AgentEvent> buffer = buffers.get(runId);
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    public int subscriberCount(String runId) {
        return subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>()).size();
    }

    public void unregisterRun(String runId, String traceId) {
        if (traceId != null) {
            traceToRun.remove(traceId);
        }
        buffers.remove(runId);
        CopyOnWriteArrayList<SseEmitter> runSubscribers = subscribers.remove(runId);
        if (runSubscribers == null) {
            return;
        }
        for (SseEmitter emitter : runSubscribers) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // emitter already completed
            }
        }
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).max(1000L, ${openscout.events.heartbeat-seconds:15} * 1000L)}")
    public void publishHeartbeats() {
        if (!properties.getEvents().isEnabled()) {
            return;
        }
        traceToRun.forEach((traceId, runId) -> {
            if (subscriberCount(runId) > 0) {
                publish(new AgentEvent(runId, traceId, "heartbeat", null,
                        null, null, null, "heartbeat", null, 0, Instant.now()));
            }
        });
    }

    private void publish(AgentEvent event) {
        if (!properties.getEvents().isEnabled()) {
            return;
        }
        appendToBuffer(event);
        for (SseEmitter emitter : subscribers.getOrDefault(event.runId(), new CopyOnWriteArrayList<>())) {
            send(event.runId(), emitter, event);
        }
    }

    private void appendToBuffer(AgentEvent event) {
        Deque<AgentEvent> buffer = buffers.computeIfAbsent(event.runId(), ignored -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(event);
            int maxSize = Math.max(1, properties.getEvents().getBufferSize());
            while (buffer.size() > maxSize) {
                buffer.removeFirst();
            }
        }
    }

    private void send(String runId, SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException | IllegalStateException ex) {
            removeSubscriber(runId, emitter);
            log.debug("Agent SSE event send failed: {}", ex.getMessage());
        }
    }

    private void completeSubscribers(String runId) {
        if (runId == null) {
            return;
        }
        for (SseEmitter emitter : subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>())) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // emitter already completed
            }
            removeSubscriber(runId, emitter);
        }
    }

    private boolean isTerminal(AgentEvent event) {
        return "run_completed".equals(event.type()) || "run_failed".equals(event.type());
    }

    private void removeSubscriber(String runId, SseEmitter emitter) {
        List<SseEmitter> runSubscribers = subscribers.get(runId);
        if (runSubscribers != null) {
            runSubscribers.remove(emitter);
        }
    }

    private String toEventType(String toolName) {
        return switch (toolName) {
            case "agent_plan_created" -> "plan_created";
            case "agent_step_started" -> "step_started";
            case "agent_step_finished" -> "step_finished";
            case "agent_observation_created" -> "observation_created";
            case "agent_tool_started" -> "tool_started";
            case "agent_tool_finished" -> "tool_finished";
            case "agent_tool_failed" -> "tool_failed";
            default -> toolName;
        };
    }

    private String tryExtractStepId(String inputSummary) {
        if (inputSummary == null || !inputSummary.startsWith("stepId=")) {
            return null;
        }
        int end = inputSummary.indexOf(' ');
        if (end < 0) {
            return inputSummary.substring("stepId=".length());
        }
        return inputSummary.substring("stepId=".length(), end);
    }
}
