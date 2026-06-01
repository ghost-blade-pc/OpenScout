package com.openscout.agent.tool;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.agent.runtime.AgentContext;
import com.openscout.agent.runtime.AgentRuntimeMode;
import com.openscout.agent.runtime.PlanStep;
import com.openscout.config.OpenScoutProperties;
import com.openscout.learning.LearningPlanResponse;
import com.openscout.learning.LearningTaskResponse;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyAnswerToolTest {

    private OpenScoutProperties properties;
    private TraceService traceService;
    private VerifyAnswerTool tool;

    @BeforeEach
    void setUp() {
        properties = new OpenScoutProperties();
        properties.getPersistence().setEnabled(false);
        traceService = new TraceService(properties, null);
        tool = new VerifyAnswerTool(properties, traceService);
    }

    @Test
    void shouldReturnSuccessWhenDisabled() {
        properties.getVerifier().setEnabled(false);
        AgentTrace trace = traceService.start("test");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.MOCK);
        PlanStep step = new PlanStep("step-9", "verify_answer", "verify", "source=answer", true);

        ToolResult result = tool.execute(new ToolRequest(step, context, trace));

        assertThat(result.status()).isEqualTo(com.openscout.agent.runtime.PlanStepStatus.SUCCESS);
        assertThat(result.outputSummary()).contains("verifier_disabled");
    }

    @Test
    void shouldReturnSuccessWhenNoIssues() {
        AgentTrace trace = traceService.start("test");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.MOCK);
        context.setAnswer("推荐 **spring-projects/spring-ai**：评分 75 分，文档完善。");
        context.setRecommendations(List.of(recommendation("spring-projects/spring-ai", 75,
                List.of("docs:README 5000+ chars", "learning:has examples"))));
        context.setLearningPlan(learningPlan(List.of(
                task(1, "阅读 spring-ai README", "阅读 spring-projects/spring-ai 的文档")
        )));
        PlanStep step = new PlanStep("step-9", "verify_answer", "verify", "source=answer", true);

        ToolResult result = tool.execute(new ToolRequest(step, context, trace));

        assertThat(result.status()).isEqualTo(com.openscout.agent.runtime.PlanStepStatus.SUCCESS);
        assertThat(result.outputSummary()).contains("allOk=true");
    }

    @Test
    void shouldDetectScoreTampering() {
        AgentTrace trace = traceService.start("test");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.MOCK);
        context.setAnswer("推荐 **spring-projects/spring-ai**：评分 90 分。");
        context.setRecommendations(List.of(recommendation("spring-projects/spring-ai", 75,
                List.of("docs:README 5000+"))));
        PlanStep step = new PlanStep("step-9", "verify_answer", "verify", "source=answer", true);

        ToolResult result = tool.execute(new ToolRequest(step, context, trace));

        assertThat(result.status()).isEqualTo(com.openscout.agent.runtime.PlanStepStatus.SUCCESS);
        assertThat(result.outputSummary()).contains("scoreIntegrity=issues");
    }

    @Test
    void shouldDetectEvidenceClaimIssues() {
        AgentTrace trace = traceService.start("test");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.MOCK);
        context.setAnswer("推荐 **repo**：该项目文档完善，有丰富的示例代码。");
        context.setRecommendations(List.of(recommendation("owner/repo", 75,
                List.of("activity:recent commits"))));
        PlanStep step = new PlanStep("step-9", "verify_answer", "verify", "source=answer", true);

        ToolResult result = tool.execute(new ToolRequest(step, context, trace));

        assertThat(result.status()).isEqualTo(com.openscout.agent.runtime.PlanStepStatus.SUCCESS);
        assertThat(result.outputSummary()).contains("evidenceClaims=issues");
    }

    @Test
    void shouldDetectLearningPlanHallucination() {
        AgentTrace trace = traceService.start("test");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.MOCK);
        context.setAnswer("推荐 spring-projects/spring-ai。");
        context.setRecommendations(List.of(recommendation("spring-projects/spring-ai", 75,
                List.of("docs:README 3000+"))));
        context.setLearningPlan(learningPlan(List.of(
                task(1, "研究 fake/lib", "深入了解 fake/lib 的源码")
        )));
        PlanStep step = new PlanStep("step-9", "verify_answer", "verify", "source=answer", true);

        ToolResult result = tool.execute(new ToolRequest(step, context, trace));

        assertThat(result.status()).isEqualTo(com.openscout.agent.runtime.PlanStepStatus.SUCCESS);
        assertThat(result.outputSummary()).contains("learningPlan=issues");
    }

    @Test
    void shouldHandleNullAnswerAndPlan() {
        AgentTrace trace = traceService.start("test");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.MOCK);
        context.setRecommendations(Collections.emptyList());
        PlanStep step = new PlanStep("step-9", "verify_answer", "verify", "source=answer", true);

        ToolResult result = tool.execute(new ToolRequest(step, context, trace));

        assertThat(result.status()).isEqualTo(com.openscout.agent.runtime.PlanStepStatus.SUCCESS);
        assertThat(result.outputSummary()).contains("allOk=true");
    }

    @Test
    void shouldRecordTraceEventOnCompletion() {
        AgentTrace trace = traceService.start("test");
        AgentContext context = new AgentContext("learn Java", AgentRuntimeMode.MOCK);
        context.setAnswer("推荐 **repo**：评分 75 分。");
        context.setRecommendations(List.of(recommendation("owner/repo", 75,
                List.of("docs:README 2000+"))));
        PlanStep step = new PlanStep("step-9", "verify_answer", "verify", "source=answer", true);

        tool.execute(new ToolRequest(step, context, trace));

        boolean hasVerifyEvent = trace.getToolCalls().stream()
                .anyMatch(tc -> "verify_completed".equals(tc.toolName()));
        assertThat(hasVerifyEvent).isTrue();
    }

    private ProjectRecommendation recommendation(String fullName, int totalScore, List<String> evidence) {
        return new ProjectRecommendation(
                fullName, "desc", "Java", 1000L, "2024-01-01",
                new com.openscout.scoring.ProjectScore(totalScore, 20, 15, 25, 10, 5, evidence),
                "reason"
        );
    }

    private LearningPlanResponse learningPlan(List<LearningTaskResponse> tasks) {
        return new LearningPlanResponse(1L, "learn Java", "Java", 7, false, tasks);
    }

    private LearningTaskResponse task(int dayNo, String title, String detail) {
        return new LearningTaskResponse(null, dayNo, title, detail, "完成笔记", "PENDING");
    }
}
