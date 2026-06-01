package com.openscout.agent.event;

import com.openscout.config.OpenScoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEventPublisherTest {

    @Test
    void shouldKeepBoundedEventBuffer() {
        OpenScoutProperties properties = new OpenScoutProperties();
        properties.getEvents().setBufferSize(2);
        AgentEventPublisher publisher = new AgentEventPublisher(properties);
        publisher.registerRun("run-1", "trace-1");

        publisher.publishTraceEvent("trace-1", "agent_step_started", "stepId=step-1", "one", 0, "SUCCESS", null);
        publisher.publishTraceEvent("trace-1", "agent_tool_started", "stepId=step-1", "two", 0, "SUCCESS", null);
        publisher.publishTraceEvent("trace-1", "agent_tool_finished", "stepId=step-1", "three", 1, "SUCCESS", null);

        assertThat(publisher.bufferedEvents("run-1"))
                .extracting(AgentEvent::type)
                .containsExactly("tool_started", "tool_finished");
    }

    @Test
    void shouldRegisterSseSubscriberForExistingRun() {
        OpenScoutProperties properties = new OpenScoutProperties();
        AgentEventPublisher publisher = new AgentEventPublisher(properties);
        publisher.registerRun("run-1", "trace-1");
        publisher.publishRunStarted("run-1", "trace-1", "RUNNING");

        publisher.subscribe("run-1");

        assertThat(publisher.subscriberCount("run-1")).isEqualTo(1);
    }

    @Test
    void shouldPublishTerminalEventAndCompleteSubscribers() {
        OpenScoutProperties properties = new OpenScoutProperties();
        AgentEventPublisher publisher = new AgentEventPublisher(properties);
        publisher.registerRun("run-1", "trace-1");
        publisher.subscribe("run-1");

        publisher.publishRunCompleted("trace-1", "SUCCEEDED", 12);

        assertThat(publisher.subscriberCount("run-1")).isZero();
        assertThat(publisher.bufferedEvents("run-1"))
                .extracting(AgentEvent::type)
                .contains("run_completed");
        assertThat(publisher.bufferedEvents("run-1"))
                .filteredOn(event -> "run_completed".equals(event.type()))
                .extracting(AgentEvent::status)
                .containsExactly("SUCCEEDED");
    }

    @Test
    void shouldRejectSubscribeWhenEventsDisabled() {
        OpenScoutProperties properties = new OpenScoutProperties();
        properties.getEvents().setEnabled(false);
        AgentEventPublisher publisher = new AgentEventPublisher(properties);

        assertThatThrownBy(() -> publisher.subscribe("run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }
}
