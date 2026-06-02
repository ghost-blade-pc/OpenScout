package com.openscout.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内固定窗口限流服务。
 * <p>
 * 以 quotaKey（API Key 或 remote address）为维度计数，窗口内超过阈值返回 false。
 * 窗口到期自动重置。注意：重启后计数清空，多实例不共享。
 * </p>
 */
public class RateLimitService {

    private static final int MAX_MAP_SIZE = 10_000;

    private final int maxRequestsPerWindow;
    private final long windowMillis;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitService(int maxRequestsPerWindow, int windowSeconds) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMillis = windowSeconds * 1000L;
    }

    /**
     * 尝试消费一次配额。
     *
     * @param quotaKey 配额键（通常是 API Key 或 IP）
     * @return true 允许；false 超限
     */
    public boolean tryConsume(String quotaKey) {
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.compute(quotaKey, (key, existing) -> {
            if (existing == null || Math.max(0, now - existing.windowStart) >= windowMillis) {
                return new WindowCounter(now, 1);
            }
            existing.count++;
            return existing;
        });
        // Prevent unbounded map growth: sweep expired entries when over threshold
        if (counters.size() > MAX_MAP_SIZE) {
            sweepExpired(now);
        }
        return counter.count <= maxRequestsPerWindow;
    }

    /**
     * 当前窗口剩余配额（用于测试和监控）。
     */
    public int remaining(String quotaKey) {
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.compute(quotaKey, (key, existing) -> {
            if (existing == null) {
                return null;
            }
            if (Math.max(0, now - existing.windowStart) >= windowMillis) {
                return null; // expired → remove
            }
            return existing; // keep as-is
        });
        if (counter == null) {
            return maxRequestsPerWindow;
        }
        return (int) Math.max(0, maxRequestsPerWindow - counter.count);
    }

    /**
     * 清除指定 key 的计数（用于测试）。
     */
    void clear(String quotaKey) {
        counters.remove(quotaKey);
    }

    /**
     * 移除所有已过期的窗口条目。
     */
    private void sweepExpired(long now) {
        counters.entrySet().removeIf(entry ->
                Math.max(0, now - entry.getValue().windowStart) >= windowMillis);
    }

    private static class WindowCounter {
        final long windowStart;
        volatile long count;

        WindowCounter(long windowStart, long count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
