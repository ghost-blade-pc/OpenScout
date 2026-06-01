package com.openscout.agent.run;

import com.openscout.agent.AgentAskRequest;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.event.AgentEvent;
import com.openscout.agent.event.AgentEventPublisher;
import com.openscout.agent.runtime.AgentRuntimeResult;
import com.openscout.agent.runtime.PlanExecutor;
import com.openscout.config.OpenScoutProperties;
import com.openscout.scoring.ProjectScore;
import com.openscout.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {

    @Test
    void shouldCreateRunAndStoreSuccessfulResult() {
        Fixture fixture = new Fixture();
        ProjectRecommendation recommendation = recommendation();
        when(fixture.planExecutor.execute(eq("learn Java"), any()))
                .thenReturn(new AgentRuntimeResult("answer", List.of(recommendation), null, "example/repo=80"));

        AgentRunCreateResponse created = fixture.service.createRun(new AgentAskRequest("learn Java", null, null));
        AgentRunResponse result = awaitTerminal(fixture.service, created.runId());

        assertThat(created.traceId()).isNotBlank();
        assertThat(created.eventsUrl()).isEqualTo("/api/agent/runs/" + created.runId() + "/events");
        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(result.answer()).isEqualTo("answer");
        assertThat(result.recommendations()).containsExactly(recommendation);
        awaitCondition(() -> fixture.publisher.bufferedEvents(created.runId()).stream()
                .anyMatch(event -> "run_completed".equals(event.type())));
        assertThat(fixture.publisher.bufferedEvents(created.runId()))
                .extracting(AgentEvent::type)
                .contains("run_started", "run_completed");
        fixture.service.shutdown();
    }

    @Test
    void shouldPublishTerminalEventAfterRunStateIsTerminal() {
        OpenScoutProperties properties = new OpenScoutProperties();
        AtomicReference<AgentRunService> serviceRef = new AtomicReference<>();
        AtomicReference<AgentRunStatus> statusWhenEventPublished = new AtomicReference<>();
        AtomicReference<String> eventStatus = new AtomicReference<>();
        AgentEventPublisher publisher = new AgentEventPublisher(properties) {
            @Override
            public void publishRunCompleted(String traceId, String status, long latencyMs) {
                serviceRef.get().findRunByTraceId(traceId)
                        .ifPresent(run -> statusWhenEventPublished.set(run.status()));
                eventStatus.set(status);
                super.publishRunCompleted(traceId, status, latencyMs);
            }
        };
        TraceService traceService = new TraceService(properties, null, publisher);
        PlanExecutor planExecutor = mock(PlanExecutor.class);
        AgentRunService service = new AgentRunService(traceService, planExecutor, properties, publisher);
        serviceRef.set(service);
        when(planExecutor.execute(eq("learn Java"), any()))
                .thenReturn(new AgentRuntimeResult("answer", List.of(), null, "no recommendations"));

        AgentRunCreateResponse created = service.createRun(new AgentAskRequest("learn Java", null, null));
        AgentRunResponse result = awaitTerminal(service, created.runId());
        awaitCondition(() -> statusWhenEventPublished.get() != null);

        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(statusWhenEventPublished.get()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(eventStatus.get()).isEqualTo("SUCCEEDED");
        service.shutdown();
    }

    @Test
    void shouldStoreFailedRunWithoutStackTrace() {
        Fixture fixture = new Fixture();
        when(fixture.planExecutor.execute(eq("learn Java"), any()))
                .thenThrow(new RuntimeException("collector down"));

        AgentRunCreateResponse created = fixture.service.createRun(new AgentAskRequest("learn Java", null, null));
        AgentRunResponse result = awaitTerminal(fixture.service, created.runId());

        assertThat(result.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(result.errorSummary()).contains("collector down");
        assertThat(result.errorSummary()).doesNotContain("RuntimeException");
        awaitCondition(() -> fixture.publisher.bufferedEvents(created.runId()).stream()
                .anyMatch(event -> "run_failed".equals(event.type())));
        assertThat(fixture.publisher.bufferedEvents(created.runId()))
                .filteredOn(event -> "run_failed".equals(event.type()))
                .extracting(AgentEvent::status)
                .containsExactly("FAILED");
        fixture.service.shutdown();
    }

    @Test
    void shouldRejectRunWhenEventsDisabled() {
        Fixture fixture = new Fixture();
        fixture.properties.getEvents().setEnabled(false);

        assertThatThrownBy(() -> fixture.service.createRun(new AgentAskRequest("learn Java", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        fixture.service.shutdown();
    }

    @Test
    void shouldRejectRunWhenActiveLimitReached() {
        Fixture fixture = new Fixture();
        fixture.properties.getEvents().setMaxActiveRuns(0);

        assertThatThrownBy(() -> fixture.service.createRun(new AgentAskRequest("learn Java", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too many active");
        fixture.service.shutdown();
    }

    @Test
    void shouldReleaseActiveSlotAfterRunCompletes() {
        Fixture fixture = new Fixture();
        fixture.properties.getEvents().setMaxActiveRuns(1);
        when(fixture.planExecutor.execute(any(), any()))
                .thenReturn(new AgentRuntimeResult("answer", List.of(), null, "no recommendations"));

        AgentRunCreateResponse first = fixture.service.createRun(new AgentAskRequest("learn Java", null, null));
        awaitTerminal(fixture.service, first.runId());
        awaitCondition(() -> fixture.service.activeRunCountForTest() == 0);
        AgentRunCreateResponse second = fixture.service.createRun(new AgentAskRequest("learn Go", null, null));
        AgentRunResponse result = awaitTerminal(fixture.service, second.runId());

        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        fixture.service.shutdown();
    }

    @Test
    void shouldRejectConcurrentRunWhenActiveSlotIsReserved() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.getEvents().setMaxActiveRuns(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.planExecutor.execute(any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new AgentRuntimeResult("answer", List.of(), null, "no recommendations");
        });

        AgentRunCreateResponse first = fixture.service.createRun(new AgentAskRequest("learn Java", null, null));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> fixture.service.createRun(new AgentAskRequest("learn Go", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too many active");

        release.countDown();
        awaitTerminal(fixture.service, first.runId());
        fixture.service.shutdown();
    }

    @Test
    void shouldCleanupCompletedRunAndEventBufferAfterRetentionWindow() {
        Fixture fixture = new Fixture();
        fixture.properties.getEvents().setCompletedRetentionSeconds(0);
        when(fixture.planExecutor.execute(eq("learn Java"), any()))
                .thenReturn(new AgentRuntimeResult("answer", List.of(), null, "no recommendations"));
        AgentRunCreateResponse created = fixture.service.createRun(new AgentAskRequest("learn Java", null, null));
        awaitTerminal(fixture.service, created.runId());

        fixture.service.cleanupCompletedRuns();

        assertThat(fixture.service.findRun(created.runId())).isEmpty();
        assertThat(fixture.publisher.bufferedEvents(created.runId())).isEmpty();
        fixture.service.shutdown();
    }

    private AgentRunResponse awaitTerminal(AgentRunService service, String runId) {
        long deadline = System.currentTimeMillis() + 2000;
        AgentRunResponse latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = service.findRun(runId).orElseThrow();
            if (latest.status() == AgentRunStatus.SUCCEEDED || latest.status() == AgentRunStatus.FAILED) {
                return latest;
            }
            Thread.yield();
        }
        assertThat(latest)
                .as("run %s should reach terminal status before timeout", runId)
                .isNotNull();
        assertThat(latest.status())
                .as("run %s should reach terminal status before timeout", runId)
                .isIn(AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED);
        return latest;
    }

    private void awaitCondition(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.yield();
        }
        assertThat(condition.getAsBoolean())
                .as("condition should become true before timeout")
                .isTrue();
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

    private static class Fixture {
        private final OpenScoutProperties properties = new OpenScoutProperties();
        private final AgentEventPublisher publisher = new AgentEventPublisher(properties);
        private final TraceService traceService = new TraceService(properties, null, publisher);
        private final PlanExecutor planExecutor = mock(PlanExecutor.class);
        private final AgentRunService service = new AgentRunService(traceService, planExecutor, properties, publisher);
    }
}
