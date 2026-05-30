package com.openscout.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * LLM (DeepSeek via OpenAI 协议) 配置。
 * <p>
 * 手动创建 {@link ChatClient} Bean，在 DEEPSEEK_API_KEY 缺失或 ChatModel 不可用时
 * 返回 null，由调用方（GoalInterpreter / AnswerGenerator）自行 fallback 到模板回答。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Value("${DEEPSEEK_API_KEY:}")
    private String apiKey;

    @Autowired(required = false)
    private ChatModel chatModel;

    @Bean
    @Nullable
    public ChatClient chatClient() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DEEPSEEK_API_KEY not set — LLM features disabled, will use template answers");
            return null;
        }
        if (chatModel == null) {
            log.warn("ChatModel not available (check spring.ai.model.chat config) — LLM features disabled");
            return null;
        }
        log.info("LLM ChatClient initialized with DeepSeek endpoint");
        return ChatClient.builder(chatModel).build();
    }
}
