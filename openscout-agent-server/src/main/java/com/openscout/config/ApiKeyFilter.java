package com.openscout.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openscout.persistence.user.UserEntity;
import com.openscout.persistence.user.UserMapper;
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
 * API Key 保护过滤器。
 * <p>
 * 默认关闭（{@code openscout.security.enabled=false}），启用后：
 * <ul>
 *   <li>优先从 {@code users} 表查找 API Key，命中则注入 {@code userId} 到 request attribute</li>
 *   <li>兼容旧版预共享密钥模式（{@code OPSCOUT_API_KEY} 环境变量）</li>
 * </ul>
 * /health 等健康检查端点始终豁免。
 * </p>
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final Set<String> EXEMPT_PATHS = Set.of("/health", "/actuator/health");

    /** 注入到 request attribute 的 userId 键名 */
    public static final String USER_ID_ATTR = "openscout.userId";
    /** 注入到 request attribute 的 userRole 键名 */
    public static final String USER_ROLE_ATTR = "openscout.userRole";

    private final OpenScoutProperties.Security security;
    private final UserMapper userMapper;

    public ApiKeyFilter(OpenScoutProperties.Security security, UserMapper userMapper) {
        this.security = security;
        this.userMapper = userMapper;
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

        if (actualKey == null || actualKey.isBlank()) {
            log.warn("API Key missing for {} {}", request.getMethod(), path);
            writeUnauthorized(response, "API key required");
            return;
        }

        // 1) 尝试从 users 表查找
        if (userMapper != null) {
            try {
                LambdaQueryWrapper<UserEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(UserEntity::getApiKey, actualKey)
                  .eq(UserEntity::getEnabled, 1);
                UserEntity user = userMapper.selectOne(qw);
                if (user != null) {
                    request.setAttribute(USER_ID_ATTR, user.getId());
                    request.setAttribute(USER_ROLE_ATTR, user.getRole());
                    filterChain.doFilter(request, response);
                    return;
                }
            } catch (Exception e) {
                log.warn("User lookup failed, falling back to static key check: {}", e.getMessage());
            }
        }

        // 2) 兼容旧版预共享密钥
        String expectedKey = security.getApiKey();
        if (expectedKey != null && !expectedKey.isBlank() && Objects.equals(expectedKey, actualKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("API Key invalid for {} {}", request.getMethod(), path);
        writeUnauthorized(response, "API key invalid");
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"UNAUTHORIZED\"}");
    }
}
