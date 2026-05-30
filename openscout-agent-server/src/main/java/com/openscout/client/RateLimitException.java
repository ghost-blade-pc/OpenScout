package com.openscout.client;

/**
 * GitHub API 限流（429 / RATE_LIMITED），调用方可以等待 retryAfterSeconds 后重试。
 */
public class RateLimitException extends CollectorException {

    private final int retryAfterSeconds;

    public RateLimitException(String traceId, String message, int retryAfterSeconds) {
        super(traceId, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
