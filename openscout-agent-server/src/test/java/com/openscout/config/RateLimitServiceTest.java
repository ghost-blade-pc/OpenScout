package com.openscout.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        // max 5 requests per 60s window
        rateLimitService = new RateLimitService(5, 60);
    }

    @Test
    void shouldAllowWithinWindow() {
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimitService.tryConsume("key-a"))
                    .as("request %d should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void shouldRejectWhenExceeded() {
        for (int i = 0; i < 5; i++) {
            rateLimitService.tryConsume("key-b");
        }
        // 6th should be rejected
        assertThat(rateLimitService.tryConsume("key-b")).isFalse();
    }

    @Test
    void shouldIsolateDifferentKeys() {
        // exhaust key-a
        for (int i = 0; i < 5; i++) {
            rateLimitService.tryConsume("key-a");
        }
        assertThat(rateLimitService.tryConsume("key-a")).isFalse();

        // key-b should still be fine
        assertThat(rateLimitService.tryConsume("key-b")).isTrue();
    }

    @Test
    void shouldReportRemaining() {
        assertThat(rateLimitService.remaining("key-c")).isEqualTo(5);
        rateLimitService.tryConsume("key-c");
        assertThat(rateLimitService.remaining("key-c")).isEqualTo(4);
    }

    @Test
    void shouldResetAfterWindowExpires() {
        // Use a short window: 1 second, max 1
        RateLimitService shortWindow = new RateLimitService(1, 1);

        assertThat(shortWindow.tryConsume("key-d")).isTrue();
        assertThat(shortWindow.tryConsume("key-d")).isFalse();

        // Wait for window to expire
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(shortWindow.tryConsume("key-d"))
                .as("should allow after window reset")
                .isTrue();
    }
}
