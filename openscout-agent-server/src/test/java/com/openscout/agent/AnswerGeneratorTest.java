package com.openscout.agent;

import com.openscout.config.OpenScoutProperties;
import com.openscout.scoring.ProjectScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnswerGeneratorTest {

    private AnswerGenerator answerGenerator;

    @BeforeEach
    void setUp() {
        answerGenerator = new AnswerGenerator(new OpenScoutProperties());
    }

    @Test
    void shouldReturnTemplateAnswerWhenLlmDisabled() {
        List<ProjectRecommendation> recs = List.of(
                new ProjectRecommendation(
                        "spring-projects/spring-ai",
                        "Spring AI framework",
                        "Java",
                        12000,
                        "2026-05-20T00:00:00Z",
                        new ProjectScore(85, 20, 16, 30, 10, 9, List.of("updated within 180 days")),
                        "匹配度很高"
                )
        );
        String answer = answerGenerator.generate("我想学 Spring AI", recs);
        assertNotNull(answer);
        assertTrue(answer.contains("spring-projects/spring-ai"));
        assertTrue(answer.contains("85"));
        assertTrue(answer.contains("LLM 不可用") || answer.contains("规则评分"));
    }

    @Test
    void shouldReturnEmptyMessageForNoRecommendations() {
        String answer = answerGenerator.generate("test", List.of());
        assertNotNull(answer);
        assertTrue(answer.contains("暂未找到"));
    }

    @Test
    void shouldReturnEmptyMessageForNullRecommendations() {
        String answer = answerGenerator.generate("test", null);
        assertNotNull(answer);
        assertTrue(answer.contains("暂未找到"));
    }

    @Test
    void shouldBuildPromptWithScoreDetails() {
        List<ProjectRecommendation> recs = List.of(
                new ProjectRecommendation(
                        "test/repo",
                        "A test project",
                        "Java",
                        1000,
                        "2026-01-01T00:00:00Z",
                        new ProjectScore(70, 15, 10, 20, 15, 10, List.of("evidence1", "evidence2")),
                        "test reason"
                )
        );
        String prompt = answerGenerator.buildUserPrompt("learn java", recs);
        assertTrue(prompt.contains("learn java"));
        assertTrue(prompt.contains("test/repo"));
        assertTrue(prompt.contains("总分70"));
        assertTrue(prompt.contains("活跃度15"));
        assertTrue(prompt.contains("evidence1"));
    }
}
