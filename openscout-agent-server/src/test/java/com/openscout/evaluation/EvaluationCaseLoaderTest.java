package com.openscout.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.agent.runtime.AgentRuntimeMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationCaseLoaderTest {

    private final EvaluationCaseLoader loader = new EvaluationCaseLoader(new ObjectMapper().findAndRegisterModules());

    @Test
    void shouldLoadDefaultCases() {
        List<EvaluationCase> cases = loader.loadDefaultCases();

        assertThat(cases).isNotEmpty();
        assertThat(cases.get(0).id()).isEqualTo("mock-spring-ai-agent");
        assertThat(cases.get(0).mode()).isEqualTo(AgentRuntimeMode.MOCK);
        assertThat(cases.get(0).expectations().expectedTraceEvents())
                .contains("repo_search_mock", "verify_completed");
    }

    @Test
    void shouldRejectInvalidCase() {
        EvaluationCase invalid = new EvaluationCase(
                "",
                "learn",
                AgentRuntimeMode.MOCK,
                false,
                List.of(),
                EvaluationExpectations.empty(),
                EvaluationThresholds.defaults()
        );

        assertThatThrownBy(() -> loader.validate(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }
}
