package com.openscout.agent.run;

import com.openscout.agent.AgentAskRequest;
import com.openscout.agent.event.AgentEventPublisher;
import com.openscout.agent.runtime.AgentRuntimeResult;
import com.openscout.agent.runtime.PlanExecutor;
import com.openscout.client.RateLimitException;
import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final TraceService traceService;
    private final PlanExecutor planExecutor;
    private final OpenScoutProperties properties;
    private final AgentEventPublisher eventPublisher;
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final AtomicInteger activeRunCount = new AtomicInteger();

    public AgentRunService(TraceService traceService,
                           PlanExecutor planExecutor,
                           OpenScoutProperties properties,
                           AgentEventPublisher eventPublisher) {
        this.traceService = traceService;
        this.planExecutor = planExecutor;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.executorService = Executors.newFixedThreadPool(Math.max(1, properties.getEvents().getExecutorThreads()));
    }

    public AgentRunCreateResponse createRun(AgentAskRequest request) {
        ensureEnabled();
        String question = request.effectiveQuestion();
        if (question.isBlank()) {
            throw new IllegalArgumentException("question or goal must not be blank");
        }
        if (!reserveActiveRunSlot()) {
            throw new IllegalStateException("too many active agent runs");
        }

        AgentTrace trace = traceService.start(question);
        String runId = UUID.randomUUID().toString();
        AgentRun run = new AgentRun(runId, trace.getTraceId(), question, Instant.now());
        run.markRunning();
        runs.put(runId, run);
        eventPublisher.registerRun(runId, trace.getTraceId());
        eventPublisher.publishRunStarted(runId, trace.getTraceId(), run.getStatus().name());

        try {
            executorService.submit(() -> executeRun(run, trace));
        } catch (RejectedExecutionException ex) {
            markRunFailed(run, trace, "Agent run executor rejected task: " + ex.getMessage(), ex);
        }
        return new AgentRunCreateResponse(runId, trace.getTraceId(), run.getStatus(), eventsUrl(runId));
    }

    public Optional<AgentRunResponse> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId)).map(this::toResponse);
    }

    Optional<AgentRunResponse> findRunByTraceId(String traceId) {
        return runs.values().stream()
                .filter(run -> run.getTraceId().equals(traceId))
                .findFirst()
                .map(this::toResponse);
    }

    int activeRunCountForTest() {
        return activeRunCount.get();
    }

    public Optional<SseEmitter> subscribe(String runId) {
        ensureEnabled();
        if (!runs.containsKey(runId)) {
            return Optional.empty();
        }
        return Optional.of(eventPublisher.subscribe(runId));
    }

    public void executeRun(AgentRun run, AgentTrace trace) {
        AgentRuntimeResult result;
        try {
            result = planExecutor.execute(run.getQuestion(), trace);
        } catch (RateLimitException ex) {
            String msg = ex.getRetryAfterSeconds() > 0
                    ? "GitHub API 限流，请等待 " + ex.getRetryAfterSeconds() + " 秒后重试"
                    : "GitHub API 限流，请稍后重试或配置 GITHUB_TOKEN 提升限额";
            markRunFailed(run, trace, msg, ex);
            return;
        } catch (RuntimeException ex) {
            markRunFailed(run, trace, "Agent 执行失败，请检查 Go Collector 是否可用：" + ex.getMessage(), ex);
            return;
        }
        traceService.complete(trace, result.scoreSummary(), result.answer());
        run.markSucceeded(result.answer(), result.recommendations(), result.learningPlan(), trace.getLatencyMs());
        releaseActiveRunSlot();
        publishRunCompleted(trace, run);
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void markRunFailed(AgentRun run, AgentTrace trace, String message, RuntimeException cause) {
        traceService.fail(trace, new RuntimeException(message, cause));
        run.markFailed(traceService.sanitize(message), trace.getLatencyMs());
        releaseActiveRunSlot();
        publishRunFailed(trace, run);
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).max(1000L, ${openscout.events.completed-retention-seconds:300} * 1000L)}")
    public void cleanupCompletedRuns() {
        if (!properties.getEvents().isEnabled()) {
            return;
        }
        int retentionSeconds = Math.max(0, properties.getEvents().getCompletedRetentionSeconds());
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(retentionSeconds));
        runs.forEach((runId, run) -> {
            if (!run.isActive() && run.getCompletedAt() != null && !run.getCompletedAt().isAfter(cutoff)) {
                if (runs.remove(runId, run)) {
                    eventPublisher.unregisterRun(runId, run.getTraceId());
                }
            }
        });
    }

    private boolean reserveActiveRunSlot() {
        int maxActiveRuns = properties.getEvents().getMaxActiveRuns();
        if (maxActiveRuns <= 0) {
            return false;
        }
        while (true) {
            int current = activeRunCount.get();
            if (current >= maxActiveRuns) {
                return false;
            }
            if (activeRunCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseActiveRunSlot() {
        int prev = activeRunCount.getAndUpdate(current -> Math.max(0, current - 1));
        if (prev <= 0) {
            log.warn("Active run slot released when count was already {} — possible double release", prev);
        }
    }

    private void publishRunCompleted(AgentTrace trace, AgentRun run) {
        try {
            eventPublisher.publishRunCompleted(trace.getTraceId(), run.getStatus().name(), trace.getLatencyMs());
        } catch (RuntimeException ignored) {
            // Event publishing is best-effort and must not change the run result.
        }
    }

    private void publishRunFailed(AgentTrace trace, AgentRun run) {
        try {
            eventPublisher.publishRunFailed(trace.getTraceId(), run.getStatus().name(), run.getErrorSummary(), trace.getLatencyMs());
        } catch (RuntimeException ignored) {
            // Event publishing is best-effort and must not change the run result.
        }
    }

    private void ensureEnabled() {
        if (!properties.getEvents().isEnabled()) {
            throw new IllegalStateException("Agent events stream is disabled");
        }
    }

    private AgentRunResponse toResponse(AgentRun run) {
        return new AgentRunResponse(
                run.getRunId(),
                run.getTraceId(),
                run.getStatus(),
                run.getAnswer(),
                run.getRecommendations(),
                run.getLearningPlan(),
                run.getErrorSummary(),
                run.getLatencyMs(),
                run.getCreatedAt(),
                run.getCompletedAt(),
                eventsUrl(run.getRunId())
        );
    }

    private String eventsUrl(String runId) {
        return "/api/agent/runs/" + runId + "/events";
    }
}
