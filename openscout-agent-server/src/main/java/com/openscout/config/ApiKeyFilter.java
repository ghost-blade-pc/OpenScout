package com.openscout.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/**
 * 最小 API Key 保护过滤器。
 * <p>
 * 默认关闭（{@code openscout.security.enabled=false}），启用后要求请求携带
 * {@code X-OpenScout-Api-Key}（可配置 header 名称）匹配预共享密钥。
 * /health 等健康检查端点始终豁免。
 * </p>
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final Set<String> EXEMPT_PATHS = Set.of("/health", "/actuator/health");

    private final OpenScoutProperties.Security security;

    public ApiKeyFilter(OpenScoutProperties.Security security) {
        this.security = security;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 未启用时透传
        if (!security.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 豁免健康检查等路径
        String path = request.getRequestURI();
        if (EXEMPT_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String headerName = security.getHeaderName();
        String actualKey = request.getHeader(headerName);
        String expectedKey = security.getApiKey();

        if (actualKey == null || actualKey.isBlank()) {
            log.warn("API Key missing for {} {}", request.getMethod(), path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"API key required\",\"code\":\"UNAUTHORIZED\"}");
            return;
        }

        if (!Objects.equals(expectedKey, actualKey)) {
            log.warn("API Key invalid for {} {}", request.getMethod(), path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"API key invalid\",\"code\":\"UNAUTHORIZED\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
