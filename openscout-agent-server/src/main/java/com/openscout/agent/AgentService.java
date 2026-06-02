package com.openscout.agent;

import com.openscout.client.RateLimitException;
import com.openscout.agent.runtime.AgentRuntimeResult;
import com.openscout.agent.runtime.PlanExecutor;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.springframework.stereotype.Service;

/**
 * OpenScout Agent 编排服务。
 * <p>
 * 负责请求校验、Trace 生命周期和异常转换；具体计划和步骤执行委托给 Agent Runtime。
 * 原名 MockAgentService，阶段 6 重命名为 AgentService。
 */
@Service
public class AgentService {

    private final TraceService traceService;
    private final PlanExecutor planExecutor;

    public AgentService(TraceService traceService, PlanExecutor planExecutor) {
        this.traceService = traceService;
        this.planExecutor = planExecutor;
    }

    public AgentAskResponse ask(AgentAskRequest request) {
        return ask(request, null);
    }

    public AgentAskResponse ask(AgentAskRequest request, Long userId) {
        String question = request.effectiveQuestion();
        if (question.isBlank()) {
            throw new IllegalArgumentException("question or goal must not be blank");
        }
        AgentTrace trace = traceService.start(question, userId);
        try {
            AgentRuntimeResult result = planExecutor.execute(question, trace);
            traceService.complete(trace, result.scoreSummary(), result.answer());
            return new AgentAskResponse(trace.getTraceId(), result.answer(), result.recommendations(),
                    result.learningPlan(), trace.getLatencyMs());
        } catch (RateLimitException ex) {
            traceService.fail(trace, ex);
            String msg = ex.getRetryAfterSeconds() > 0
                    ? "GitHub API 限流，请等待 " + ex.getRetryAfterSeconds() + " 秒后重试"
                    : "GitHub API 限流，请稍后重试或配置 GITHUB_TOKEN 提升限额";
            throw new AgentCallException(trace.getTraceId(), msg, ex);
        } catch (RuntimeException ex) {
            traceService.fail(trace, ex);
            throw new AgentCallException(trace.getTraceId(),
                    "Agent 执行失败，请检查 Go Collector 是否可用：" + ex.getMessage(), ex);
        }
    }
}
