package com.openscout.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RuleBasedAgentPlanner {

    public AgentPlan plan(String userGoal, boolean mockAgent) {
        AgentRuntimeMode mode = mockAgent ? AgentRuntimeMode.MOCK : AgentRuntimeMode.REAL;
        String goalSummary = "goal=" + summarize(userGoal);
        List<PlanStep> steps = new ArrayList<>();
        steps.add(new PlanStep("step-1", "interpret_goal", "将用户目标解释为搜索关键词", goalSummary, false));
        steps.add(new PlanStep("step-2", "search_repos", "检索候选开源项目", "mode=" + mode, false));
        if (mode == AgentRuntimeMode.REAL) {
            steps.add(new PlanStep("step-3", "fetch_readme", "为 Top 仓库补充 README 证据", "maxTargets=5", true));
        }
        int offset = mode == AgentRuntimeMode.REAL ? 1 : 0;
        steps.add(new PlanStep("step-" + (3 + offset), "score_projects", "按规则评分并排序候选项目", "source=repos", false));
        steps.add(new PlanStep("step-" + (4 + offset), "generate_learning_plan", "基于 Top 推荐生成学习计划", "durationDays=7", true));
        steps.add(new PlanStep("step-" + (5 + offset), "generate_answer", "基于规则评分生成最终回答", "source=recommendations", false));
        return new AgentPlan("plan-" + UUID.randomUUID(), mode, steps);
    }

    private String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }
}
