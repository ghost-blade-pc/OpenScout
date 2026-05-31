package com.openscout.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.config.OpenScoutProperties;
import com.openscout.scoring.ProjectScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningPlanGeneratorTest {

    private final LearningPlanGenerator generator =
            new LearningPlanGenerator(new OpenScoutProperties(), new ObjectMapper());

    @Test
    void shouldGenerateSevenTemplateTasksForRecommendations() {
        LearningPlanResponse plan = generator.generate("我想学习 Spring AI", List.of(recommendation()));

        assertThat(plan.persisted()).isFalse();
        assertThat(plan.goalId()).isNull();
        assertThat(plan.durationDays()).isEqualTo(7);
        assertThat(plan.targetStack()).isEqualTo("Java");
        assertThat(plan.tasks()).hasSize(7);
        assertThat(plan.tasks()).extracting(LearningTaskResponse::dayNo)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(plan.tasks()).allMatch(task -> "TODO".equals(task.status()));
        assertThat(plan.tasks().get(0).detail()).contains("spring-projects/spring-ai");
    }

    @Test
    void shouldReturnEmptyPlanWhenNoRecommendationAvailable() {
        LearningPlanResponse plan = generator.generate("test", List.of());

        assertThat(plan.tasks()).isEmpty();
        assertThat(plan.persisted()).isFalse();
    }

    @Test
    void shouldParseTaskStatusCaseInsensitively() {
        assertThat(LearningTaskStatus.parse("done")).isEqualTo(LearningTaskStatus.DONE);
    }

    @Test
    void shouldExtractJsonFromMarkdownBlock() {
        String json = LearningPlanGenerator.extractJson("```json\n[]\n```");

        assertThat(json).isEqualTo("[]");
    }

    @Test
    void shouldRejectInvalidTaskStatus() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LearningTaskStatus.parse("INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TODO");
    }

    private ProjectRecommendation recommendation() {
        return new ProjectRecommendation(
                "spring-projects/spring-ai",
                "Spring AI application framework",
                "Java",
                12000,
                "2026-05-20T00:00:00Z",
                new ProjectScore(85, 20, 16, 30, 10, 9,
                        List.of("activity: updated within 180 days", "docs: README length >= 2000")),
                "匹配度很高"
        );
    }
}
