package com.openscout.agent.tool;

import com.openscout.agent.verifier.EvidenceClaimsChecker;
import com.openscout.agent.verifier.LearningPlanChecker;
import com.openscout.agent.verifier.ScoreIntegrityChecker;
import com.openscout.agent.verifier.VerificationIssue;
import com.openscout.agent.verifier.VerificationResult;
import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class VerifyAnswerTool implements AgentTool {

    public static final String NAME = "verify_answer";

    private static final Logger log = LoggerFactory.getLogger(VerifyAnswerTool.class);

    private final OpenScoutProperties properties;
    private final ScoreIntegrityChecker scoreIntegrityChecker;
    private final EvidenceClaimsChecker evidenceClaimsChecker;
    private final LearningPlanChecker learningPlanChecker;
    private final TraceService traceService;

    public VerifyAnswerTool(OpenScoutProperties properties,
                            TraceService traceService) {
        this.properties = properties;
        this.scoreIntegrityChecker = new ScoreIntegrityChecker();
        this.evidenceClaimsChecker = new EvidenceClaimsChecker();
        this.learningPlanChecker = new LearningPlanChecker();
        this.traceService = traceService;
    }

    @Override
    public String toolName() {
        return NAME;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Instant start = Instant.now();
        OpenScoutProperties.Verifier verifier = properties.getVerifier();

        if (!verifier.isEnabled()) {
            traceService.recordToolCall(request.trace(), "verify_completed",
                    "enabled=false",
                    "verifier_disabled",
                    Duration.between(start, Instant.now()).toMillis());
            return ToolResult.success("verifier_disabled");
        }

        String answer = request.context().getAnswer();
        var recommendations = request.context().getRecommendations();
        var learningPlan = request.context().getLearningPlan();

        VerificationResult result = new VerificationResult();

        runCheck(result, () -> scoreIntegrityChecker.check(answer, recommendations),
                result::setScoreIntegrityOk, "SCORE_CHECK_ERROR", "分数检查执行失败");
        runCheck(result, () -> evidenceClaimsChecker.check(answer, recommendations),
                result::setEvidenceClaimsOk, "EVIDENCE_CHECK_ERROR", "证据声明检查执行失败");
        runCheck(result, () -> learningPlanChecker.check(learningPlan, recommendations),
                result::setLearningPlanOk, "PLAN_CHECK_ERROR", "学习计划检查执行失败");

        long latencyMs = Duration.between(start, Instant.now()).toMillis();

        traceService.recordToolCall(request.trace(), "verify_completed",
                "scoreIntegrity=" + (result.isScoreIntegrityOk() ? "ok" : "issues")
                        + " evidenceClaims=" + (result.isEvidenceClaimsOk() ? "ok" : "issues")
                        + " learningPlan=" + (result.isLearningPlanOk() ? "ok" : "issues"),
                result.summary(),
                latencyMs);

        return ToolResult.success(result.summary());
    }

    private void runCheck(VerificationResult result, Supplier<VerificationResult> checkFn,
                          Consumer<Boolean> flagSetter, String errorCode, String errorDesc) {
        try {
            VerificationResult checkResult = checkFn.get();
            if (!checkResult.allOk()) {
                flagSetter.accept(false);
                result.getIssues().addAll(checkResult.getIssues());
            }
        } catch (Exception e) {
            log.warn("验证检查异常（不阻断主流程）：{} {}", errorDesc, e.getMessage());
            flagSetter.accept(false);
            result.addIssue(VerificationIssue.warning(errorCode, errorDesc, e.getMessage()));
        }
    }
}
