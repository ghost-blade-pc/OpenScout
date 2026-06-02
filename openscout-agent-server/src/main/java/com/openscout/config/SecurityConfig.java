package com.openscout.config;

import com.openscout.persistence.user.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全与配额配置。
 * <p>
 * 注册 API Key 过滤器和入站配额过滤器。过滤器始终注册，
 * 但仅在对应 {@code openscout.security.enabled / openscout.quota.enabled} 为 true 时拦截。
 * </p>
 */
@Configuration
public class SecurityConfig {

    @Autowired(required = false)
    private UserMapper userMapper;

    @Bean
    RateLimitService rateLimitService(OpenScoutProperties properties) {
        OpenScoutProperties.Quota quota = properties.getQuota();
        return new RateLimitService(quota.getMaxRequestsPerWindow(), quota.getWindowSeconds());
    }

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(OpenScoutProperties properties) {
        ApiKeyFilter filter = new ApiKeyFilter(properties.getSecurity(), userMapper);
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<QuotaFilter> quotaFilterRegistration(OpenScoutProperties properties,
                                                                 RateLimitService rateLimitService) {
        QuotaFilter filter = new QuotaFilter(
                properties.getQuota(), properties.getSecurity(), rateLimitService);
        FilterRegistrationBean<QuotaFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(2);
        return registration;
    }
}
