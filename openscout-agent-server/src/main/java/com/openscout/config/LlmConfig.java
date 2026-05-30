package com.openscout.config;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * LLM (DeepSeek via OpenAI 协议) 手动配置。
 * <p>
 * 不依赖 Spring AI auto-config，全部手动创建以控制超时等参数。
 * DEEPSEEK_API_KEY 缺失时 ChatClient Bean 返回 null，调用方自行 fallback。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Value("${DEEPSEEK_API_KEY:}")
    private String apiKey;

    @Value("${DEEPSEEK_BASE_URL:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${DEEPSEEK_MODEL:deepseek-v4-pro}")
    private String model;

    @Bean
    @Nullable
    public ChatClient chatClient() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DEEPSEEK_API_KEY not set — LLM features disabled, will use template answers");
            return null;
        }

        // Custom RestClient with 60s read timeout for LLM API calls
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(60_000);
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(model)
                .build();

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(1));
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5000);
        retryTemplate.setBackOffPolicy(backOff);

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaultOptions)
                .retryTemplate(retryTemplate)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        log.info("LLM ChatClient initialized: baseUrl={} model={}", baseUrl, model);
        return ChatClient.builder(chatModel).build();
    }
}
