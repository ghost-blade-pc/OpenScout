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
        int idx = 1;
        steps.add(new PlanStep("step-" + idx++, "interpret_goal", "将用户目标解释为搜索关键词", goalSummary, false));
        steps.add(new PlanStep("step-" + idx++, "check_memory",
                "检查 MySQL 中是否已有新鲜的项目数据，命中则复用", "keyword from interpret_goal", true));
        steps.add(new PlanStep("step-" + idx++, "search_repos", "检索候选开源项目", "mode=" + mode, false));
        if (mode == AgentRuntimeMode.REAL) {
            steps.add(new PlanStep("step-" + idx++, "fetch_readme", "为 Top 仓库补充 README 证据", "maxTargets=5", true));
        }
        steps.add(new PlanStep("step-" + idx++, "score_projects", "按规则评分并排序候选项目", "source=repos", false));
        steps.add(new PlanStep("step-" + idx++, "evidence_react",
                "基于评分证据缺口追加有限 README 补查", "source=recommendations", true));
        steps.add(new PlanStep("step-" + idx++, "generate_learning_plan", "基于 Top 推荐生成学习计划", "durationDays=7", true));
        steps.add(new PlanStep("step-" + idx, "generate_answer", "基于规则评分生成最终回答", "source=recommendations", false));
        return new AgentPlan("plan-" + UUID.randomUUID(), mode, steps);
    }

    private String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }
}
