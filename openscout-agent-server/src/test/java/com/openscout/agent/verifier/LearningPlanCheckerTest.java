package com.openscout.agent.verifier;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.learning.LearningPlanResponse;
import com.openscout.learning.LearningTaskResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningPlanCheckerTest {

    private final LearningPlanChecker checker = new LearningPlanChecker();

    @Test
    void shouldPassWhenTaskReferencesValidRepo() {
        var rec = recommendation("spring-projects/spring-ai");
        var plan = learningPlan(List.of(
                task(1, "阅读 spring-ai README", "阅读 spring-projects/spring-ai 的 README 文档")
        ));

        VerificationResult result = checker.check(plan, List.of(rec));

        assertThat(result.isLearningPlanOk()).isTrue();
    }

    @Test
    void shouldDetectHallucinatedRepo() {
        var rec = recommendation("spring-projects/spring-ai");
        var plan = learningPlan(List.of(
                task(1, "研究 fake/repo", "深入了解 fake/repo 的架构设计")
        ));

        VerificationResult result = checker.check(plan, List.of(rec));

        assertThat(result.isLearningPlanOk()).isFalse();
        assertThat(result.getIssues()).anyMatch(i ->
                i.getCheckType().equals("PLAN_HALLUCINATION") && i.getDetail().contains("fake/repo"));
    }

    @Test
    void shouldHandleNullLearningPlan() {
        var rec = recommendation("owner/repo");
        VerificationResult result = checker.check(null, List.of(rec));
        assertThat(result.isLearningPlanOk()).isTrue();
    }

    @Test
    void shouldHandleEmptyTasks() {
        var rec = recommendation("owner/repo");
        var plan = learningPlan(Collections.emptyList());
        VerificationResult result = checker.check(plan, List.of(rec));
        assertThat(result.isLearningPlanOk()).isTrue();
    }

    @Test
    void shouldHandleEmptyRecommendations() {
        var plan = learningPlan(List.of(
                task(1, "阅读 repo", "阅读 owner/repo 文档")
        ));
        VerificationResult result = checker.check(plan, Collections.emptyList());
        assertThat(result.isLearningPlanOk()).isTrue();
    }

    @Test
    void shouldDetectHallucinationInDetailText() {
        var rec = recommendation("spring-projects/spring-boot");
        var plan = learningPlan(List.of(
                task(1, "配置项目", "使用 none/gradle 配置构建工具")
        ));

        VerificationResult result = checker.check(plan, List.of(rec));

        assertThat(result.isLearningPlanOk()).isFalse();
        assertThat(result.getIssues()).anyMatch(i ->
                i.getDetail().contains("none/gradle"));
    }

    private ProjectRecommendation recommendation(String fullName) {
        return new ProjectRecommendation(
                fullName, "desc", "Java", 1000L, "2024-01-01",
                new com.openscout.scoring.ProjectScore(75, 20, 15, 25, 10, 5,
                        List.of("docs:README 3000+")),
                "reason"
        );
    }

    private LearningPlanResponse learningPlan(List<LearningTaskResponse> tasks) {
        return new LearningPlanResponse(1L, "learn Spring AI", "Java", 7, false, tasks);
    }

    private LearningTaskResponse task(int dayNo, String title, String detail) {
        return new LearningTaskResponse(null, dayNo, title, detail, "完成学习笔记", "PENDING");
    }
}
