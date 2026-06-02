package com.openscout.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openscout.config.ApiKeyFilter;
import com.openscout.persistence.user.UserEntity;
import com.openscout.persistence.user.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Key 管理端点（仅限 admin 角色）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserMapper userMapper;

    public AdminController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 生成新的 API Key。
     */
    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, String> body,
                                          HttpServletRequest request) {
        requireAdmin(request);
        String name = body.getOrDefault("name", "user");
        String role = body.getOrDefault("role", "user");
        String apiKey = UUID.randomUUID().toString().replace("-", "");
        UserEntity user = new UserEntity();
        user.setApiKey(apiKey);
        user.setName(name);
        user.setRole(role);
        user.setEnabled(1);
        userMapper.insert(user);
        log.info("Created user id={} name={} role={}", user.getId(), name, role);
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("name", name);
        result.put("role", role);
        result.put("apiKey", apiKey);
        return result;
    }

    /**
     * 列出所有用户（不返回完整 API Key，仅前 8 位）。
     */
    @GetMapping("/users")
    public List<Map<String, Object>> listUsers(HttpServletRequest request) {
        requireAdmin(request);
        return userMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .map(u -> {
                    String masked = u.getApiKey() != null && u.getApiKey().length() > 8
                            ? u.getApiKey().substring(0, 8) + "***"
                            : "***";
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("name", u.getName());
                    m.put("role", u.getRole());
                    m.put("apiKeyPreview", masked);
                    m.put("enabled", u.getEnabled());
                    m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
                    return m;
                })
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * 吊销 API Key（软删除：设 enabled=0）。
     */
    @DeleteMapping("/users/{id}")
    public Map<String, String> revokeUser(@PathVariable Long id,
                                          HttpServletRequest request) {
        requireAdmin(request);
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + id);
        }
        user.setEnabled(0);
        userMapper.updateById(user);
        log.info("Revoked user id={} name={}", id, user.getName());
        return Map.of("status", "revoked", "id", String.valueOf(id));
    }

    private void requireAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute(ApiKeyFilter.USER_ROLE_ATTR);
        if (!"admin".equals(role)) {
            throw new SecurityException("Admin role required");
        }
    }
}
