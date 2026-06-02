package com.openscout.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 入站配额过滤器。
 * <p>
 * 对写操作入口（POST /api/agent/ask、POST /api/agent/runs）进行进程内固定窗口限流。
 * 配额键优先使用 API Key header，其次使用 client IP。
 * 超限返回 429。
 * </p>
 */
public class QuotaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(QuotaFilter.class);

    // 受配额保护的端点
    private static final Set<String> QUOTA_PATHS = Set.of("/api/agent/ask", "/api/agent/runs");

    private final OpenScoutProperties.Quota quota;
    private final OpenScoutProperties.Security security;
    private final RateLimitService rateLimitService;

    public QuotaFilter(OpenScoutProperties.Quota quota,
                       OpenScoutProperties.Security security,
                       RateLimitService rateLimitService) {
        this.quota = quota;
        this.security = security;
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!quota.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // 仅对写入口限流
        if (!"POST".equalsIgnoreCase(method) || !QUOTA_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String quotaKey = resolveQuotaKey(request);

        if (!rateLimitService.tryConsume(quotaKey)) {
            log.warn("Quota exceeded for key={}", quotaKey);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"Too many requests. Please try again later.\",\"code\":\"QUOTA_EXCEEDED\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 解析配额键：优先 API Key header，其次 remote address。
     */
    private String resolveQuotaKey(HttpServletRequest request) {
        if (security.isEnabled()) {
            String apiKey = request.getHeader(security.getHeaderName());
            if (apiKey != null && !apiKey.isBlank()) {
                return "key:" + apiKey;
            }
        }
        String ip = request.getRemoteAddr();
        return ip != null ? ip : "unknown";
    }
}
