package com.openscout.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 LLM 将用户自然语言目标提取为 GitHub 搜索关键词和技术偏好。
 * <p>
 * LLM 不可用时自动 fallback 到以原始 goal 为 keyword。
 */
@Service
public class GoalInterpreter {

    private static final Logger log = LoggerFactory.getLogger(GoalInterpreter.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
    private static final int MAX_GOAL_LENGTH = 200;
    /** 目标解释固定使用低温度以保证 JSON 输出稳定，不需要可配置。 */
    private static final double INTERPRET_TEMPERATURE = 0.3;
    private static final int INTERPRET_MAX_TOKENS = 300;

    private static final String SYSTEM_PROMPT = """
            你是一个开源项目搜索专家。用户会用自然语言描述学习目标，你需要提取：
            1. 最适合在 GitHub 搜索的关键词（1-3 个词，英文）
            2. 偏好的编程语言（如果用户提到）
            3. 关注的技术领域（如 AI、web、数据库等）

            只返回 JSON，不要其他文字：
            {"keyword": "...", "language": "...", "domain": "..."}""";

    @Autowired(required = false)
    private ChatClient chatClient;

    private final OpenScoutProperties properties;
    private final ObjectMapper objectMapper;

    public GoalInterpreter(OpenScoutProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 解释用户目标，返回搜索关键词（无 Trace 记录）。
     */
    public GoalInterpretation interpret(String userGoal) {
        return interpret(userGoal, null, null);
    }

    /**
     * 带 Trace 的解释方法（供 AgentService 调用，记录 LLM 调用到 AgentTrace）。
     */
    public GoalInterpretation interpret(String userGoal, AgentTrace trace,
                                         TraceService traceService) {
        String safeGoal = truncate(userGoal, MAX_GOAL_LENGTH);
        if (chatClient == null || !properties.getLlm().isEnabled()) {
            log.debug("LLM disabled, using raw goal as keyword");
            return fallback(safeGoal);
        }
        Instant start = Instant.now();
        try {
            String response = callLlm(safeGoal);
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            if (traceService != null && trace != null) {
                traceService.recordToolCall(trace, "llm_goal_interpret",
                        "prompt: " + truncate(safeGoal, 200),
                        "response: " + truncate(response, 300),
                        latencyMs);
            }
            return parseResponse(response, safeGoal);
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            if (traceService != null && trace != null) {
                traceService.recordToolCall(trace, "llm_goal_interpret",
                        "prompt: " + truncate(safeGoal, 200),
                        "error: " + truncate(e.getMessage(), 300),
                        latencyMs);
            }
            log.warn("GoalInterpreter LLM call failed, using fallback: {}", e.getMessage());
            return fallback(safeGoal);
        }
    }

    private String callLlm(String safeGoal) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(safeGoal)
                .options(OpenAiChatOptions.builder()
                        .temperature(INTERPRET_TEMPERATURE)
                        .maxTokens(INTERPRET_MAX_TOKENS)
                        .build())
                .call()
                .content();
    }

    private GoalInterpretation parseResponse(String response, String userGoal) {
        String json = extractJson(response);
        try {
            GoalInterpretation parsed = objectMapper.readValue(json, GoalInterpretation.class);
            if (parsed.keyword() != null && !parsed.keyword().isBlank()) {
                return parsed;
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse GoalInterpreter response as JSON: {}", json);
        }
        return fallback(userGoal);
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串。
     * 处理 LLM 可能包裹的 markdown 代码块（```json ... ```）。
     */
    static String extractJson(String response) {
        if (response == null) {
            return "{}";
        }
        Matcher matcher = JSON_BLOCK.matcher(response.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return response.trim();
    }

    private GoalInterpretation fallback(String userGoal) {
        return new GoalInterpretation(userGoal, "", "");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
