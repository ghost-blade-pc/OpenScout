package com.openscout.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.config.OpenScoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LearningPlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(LearningPlanGenerator.class);
    private static final int DURATION_DAYS = 7;
    private static final int MAX_RECOMMENDATIONS = 3;
    private static final int MAX_PROMPT_LENGTH = 1800;
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
    private static final String SYSTEM_PROMPT = """
            你是一个开源项目学习计划助手。你会收到用户目标和已经由规则引擎排序的推荐项目。
            你只能基于输入中的项目名称、描述、语言、评分和证据生成 7 天学习任务。
            严格规则：
            - 不得修改项目评分，不得编造项目模块、API 或功能
            - 不确定的内容必须写成“阅读 README/示例确认”
            - 只返回 JSON 数组，不要 markdown，不要额外解释
            - 数组必须有 7 项，每项字段为 dayNo、title、detail、expectedOutput
            """;

    @Autowired(required = false)
    private ChatClient chatClient;

    private final OpenScoutProperties properties;
    private final ObjectMapper objectMapper;

    public LearningPlanGenerator(OpenScoutProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public LearningPlanResponse generate(String goal, List<ProjectRecommendation> recommendations) {
        List<ProjectRecommendation> top = topRecommendations(recommendations);
        if (top.isEmpty()) {
            return new LearningPlanResponse(null, goal, "", DURATION_DAYS, false, List.of());
        }
        if (chatClient != null && properties.getLlm().isEnabled()) {
            try {
                List<LearningTaskResponse> enhanced = callLlm(goal, top);
                if (enhanced.size() == DURATION_DAYS) {
                    return new LearningPlanResponse(null, goal, targetStack(top), DURATION_DAYS, false, enhanced);
                }
            } catch (Exception e) {
                log.warn("Learning plan LLM enhancement failed, using rule template: {}", e.getMessage());
            }
        }
        return new LearningPlanResponse(null, goal, targetStack(top), DURATION_DAYS, false, templateTasks(goal, top));
    }

    private List<ProjectRecommendation> topRecommendations(List<ProjectRecommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        return recommendations.stream().limit(MAX_RECOMMENDATIONS).toList();
    }

    private List<LearningTaskResponse> callLlm(String goal, List<ProjectRecommendation> recommendations)
            throws JsonProcessingException {
        String prompt = truncate(buildPrompt(goal, recommendations), MAX_PROMPT_LENGTH);
        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .options(OpenAiChatOptions.builder()
                        .temperature(properties.getLlm().getTemperature())
                        .maxTokens(Math.min(properties.getLlm().getMaxTokens(), 1200))
                        .build())
                .call()
                .content();
        List<LlmTask> tasks = objectMapper.readValue(extractJson(response), new TypeReference<>() {
        });
        List<LearningTaskResponse> result = new ArrayList<>();
        for (LlmTask task : tasks) {
            if (task.dayNo() < 1 || task.dayNo() > DURATION_DAYS) {
                return List.of();
            }
            result.add(new LearningTaskResponse(
                    null,
                    task.dayNo(),
                    truncate(task.title(), 300),
                    truncate(task.detail(), 1200),
                    truncate(task.expectedOutput(), 1200),
                    LearningTaskStatus.TODO.name()
            ));
        }
        return result;
    }

    static String extractJson(String response) {
        if (response == null) {
            return "[]";
        }
        Matcher matcher = JSON_BLOCK.matcher(response.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return response.trim();
    }

    private String buildPrompt(String goal, List<ProjectRecommendation> recommendations) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户目标：").append(goal).append("\n");
        sb.append("推荐项目：\n");
        for (ProjectRecommendation rec : recommendations) {
            sb.append("- ").append(rec.fullName())
                    .append("，语言：").append(nullToEmpty(rec.language()))
                    .append("，评分：").append(rec.score().totalScore())
                    .append("，描述：").append(truncate(rec.description(), 160))
                    .append("，证据：").append(truncate(String.join("; ", rec.score().evidence()), 260))
                    .append("\n");
        }
        return sb.toString();
    }

    private List<LearningTaskResponse> templateTasks(String goal, List<ProjectRecommendation> recommendations) {
        ProjectRecommendation primary = recommendations.get(0);
        String project = primary.fullName();
        String language = nullToEmpty(primary.language()).isBlank() ? "主要技术栈" : primary.language();
        List<String> evidence = primary.score().evidence() == null ? List.of() : primary.score().evidence();
        String evidenceText = evidence.isEmpty() ? "规则评分证据" : String.join("; ", evidence);
        return List.of(
                task(1, "明确目标与项目范围",
                        "围绕「" + goal + "」阅读 " + project + " 的 README、快速开始和目录结构，确认项目解决的问题、技术栈和本周学习边界。",
                        "写出项目定位、核心模块猜测和 3 个待验证问题。"),
                task(2, "跑通本地最小示例",
                        "按照 README/示例尝试运行 " + project + " 的最小 demo；如果步骤缺失，记录缺失点并阅读示例目录确认。",
                        "形成可复现启动命令、依赖清单和失败排查记录。"),
                task(3, "梳理核心技术栈",
                        "聚焦 " + language + " 相关源码、配置和测试，整理项目如何组织入口、领域逻辑和外部依赖。",
                        "输出核心调用链草图和关键文件清单。"),
                task(4, "验证评分证据",
                        "对照 OpenScout 评分证据（" + truncate(evidenceText, 300) + "）检查 README、示例、Docker 或活跃度信息是否能支撑学习价值。",
                        "记录每条 evidence 的来源和可信度。"),
                task(5, "实现一个小改动",
                        "选择一个低风险小点，例如补充示例、调整配置或增加一个测试；改动前先确认项目贡献规范。",
                        "提交本地 patch 和验证命令输出。"),
                task(6, "提炼简历表达",
                        "基于本周已验证事实，总结项目工程亮点、可复现 demo、你完成的小改动和遇到的技术问题。",
                        "形成 3 条简历 bullet 和 3 个面试追问答案。"),
                task(7, "复盘与下一步计划",
                        "复盘 " + project + " 是否继续深入；如果不匹配目标，从推荐列表中选择下一个项目重复 Day 1-3 的验证。",
                        "输出继续深入/切换项目的判断依据和下一周计划。")
        );
    }

    private LearningTaskResponse task(int dayNo, String title, String detail, String expectedOutput) {
        return new LearningTaskResponse(null, dayNo, title, detail, expectedOutput, LearningTaskStatus.TODO.name());
    }

    private String targetStack(List<ProjectRecommendation> recommendations) {
        return recommendations.stream()
                .map(ProjectRecommendation::language)
                .filter(language -> language != null && !language.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record LlmTask(int dayNo, String title, String detail, String expectedOutput) {
    }
}
