package com.openscout.agent;

import com.openscout.config.OpenScoutProperties;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 使用 LLM 根据规则评分结果生成自然语言推荐回答。
 * <p>
 * LLM 不可用或调用失败时自动 fallback 到模板回答。
 * System prompt 严格约束 LLM 不得修改评分数字、不得编造项目特性。
 */
@Service
public class AnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerator.class);
    private static final int MAX_EVIDENCE_LENGTH = 300;

    private static final String SYSTEM_PROMPT = """
            你是一个开源项目推荐顾问。你会收到一组基于规则评分排序的项目推荐，
            每个项目包含名称、描述、语言、stars、评分详情和评分证据。

            你的任务是为用户生成个性化的推荐回答。严格遵守以下规则：
            - 评分由规则引擎计算，你不得修改任何分数数字
            - 你可以解释每个项目的优缺点，但必须引用评分证据
            - 推荐理由必须基于实际数据，不得编造项目特性
            - 如果某个维度得分低，可以给出学习建议但不否定整体推荐
            - 不得泄露系统内部实现细节

            格式要求：
            1. 先给出总体推荐摘要（100 字以内）
            2. 然后对每个推荐项目给一句推荐理由（50 字以内），以编号列表呈现
            3. 如果所有项目总分都不高，诚实告知并建议用户扩大搜索范围""";

    @Autowired(required = false)
    private ChatClient chatClient;

    private final OpenScoutProperties properties;

    public AnswerGenerator(OpenScoutProperties properties) {
        this.properties = properties;
    }

    /**
     * 基于评分结果生成自然语言推荐回答（无 Trace 记录）。
     */
    public String generate(String userGoal, List<ProjectRecommendation> recommendations) {
        return generate(userGoal, recommendations, null, null);
    }

    /**
     * 带 Trace 的生成方法（供 AgentService 调用）。
     */
    public String generate(String userGoal, List<ProjectRecommendation> recommendations,
                           AgentTrace trace, TraceService traceService) {
        if (recommendations == null || recommendations.isEmpty()) {
            return "暂未找到适合「" + userGoal + "」的候选项目。请尝试调整搜索关键词或稍后重试。";
        }
        if (chatClient == null || !properties.getLlm().isEnabled()) {
            log.debug("LLM disabled, using template answer");
            return templateAnswer(userGoal, recommendations);
        }
        String userPrompt = buildUserPrompt(userGoal, recommendations);
        Instant start = Instant.now();
        try {
            String response = callLlm(userPrompt);
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            if (traceService != null && trace != null) {
                traceService.recordToolCall(trace, "llm_answer_generate",
                        "prompt: " + truncate(userPrompt, 200),
                        "response: " + truncate(response, 300),
                        latencyMs);
            }
            return response;
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            if (traceService != null && trace != null) {
                traceService.recordToolCall(trace, "llm_answer_generate",
                        "prompt: " + truncate(userPrompt, 200),
                        "error: " + truncate(e.getMessage(), 300),
                        latencyMs);
            }
            log.warn("AnswerGenerator LLM call failed, using template answer: {}", e.getMessage());
            return templateAnswer(userGoal, recommendations);
        }
    }

    private String callLlm(String userPrompt) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .options(OpenAiChatOptions.builder()
                        .temperature(properties.getLlm().getTemperature())
                        .maxTokens(properties.getLlm().getMaxTokens())
                        .build())
                .call()
                .content();
    }

    /**
     * 将推荐列表格式化为 LLM user prompt 文本。
     */
    String buildUserPrompt(String userGoal, List<ProjectRecommendation> recommendations) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户目标：").append(userGoal).append("\n\n");
        sb.append("推荐项目：\n");
        int rank = 1;
        for (ProjectRecommendation rec : recommendations) {
            sb.append(rank++).append(". ");
            sb.append(rec.fullName());
            sb.append(" (总分").append(rec.score().totalScore()).append(")");
            sb.append("：活跃度").append(rec.score().activityScore());
            sb.append("/文档").append(rec.score().docScore());
            sb.append("/匹配").append(rec.score().matchScore());
            sb.append("/学习").append(rec.score().learningScore());
            sb.append("/简历").append(rec.score().resumeValueScore());
            sb.append("\n");
            sb.append("   描述：").append(truncate(rec.description(), 150)).append("\n");
            sb.append("   语言：").append(rec.language()).append("  stars：").append(rec.stars()).append("\n");
            String evidence = rec.score().evidence() != null
                    ? String.join("; ", rec.score().evidence())
                    : "";
            sb.append("   证据：").append(truncate(evidence, MAX_EVIDENCE_LENGTH)).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Fallback 模板回答。
     */
    private String templateAnswer(String userGoal, List<ProjectRecommendation> recommendations) {
        if (recommendations.isEmpty()) {
            return "暂未找到适合「" + userGoal + "」的候选项目。";
        }
        ProjectRecommendation best = recommendations.get(0);
        return "已基于规则评分完成项目推荐。当前最推荐 " + best.fullName()
                + "，评分 " + best.score().totalScore()
                + "（基于活跃度、文档完整度、技术匹配度、学习友好度和简历价值综合评估）。"
                + "共检索到 " + recommendations.size() + " 个候选项目。"
                + "（LLM 不可用，返回模板回答。）";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
