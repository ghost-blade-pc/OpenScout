package com.openscout.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QuotaFilterTest {

    private static final String API_KEY_HEADER = "X-OpenScout-Api-Key";

    private FilterChain chain;

    @BeforeEach
    void setUp() {
        chain = mock(FilterChain.class);
    }

    // ---- Disabled ----

    @Test
    void shouldPassThroughWhenDisabled() throws Exception {
        OpenScoutProperties.Quota quota = quota(false, 2, 60);
        OpenScoutProperties.Security security = security(false);
        RateLimitService rateLimitService = new RateLimitService(2, 60);
        QuotaFilter filter = new QuotaFilter(quota, security, rateLimitService);

        MockHttpServletRequest request = post("/api/agent/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---- Non-quota path ----

    @Test
    void shouldSkipGetTraces() throws Exception {
        OpenScoutProperties.Quota quota = quota(true, 2, 60);
        OpenScoutProperties.Security security = security(false);
        RateLimitService rateLimitService = new RateLimitService(2, 60);
        QuotaFilter filter = new QuotaFilter(quota, security, rateLimitService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/traces/abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ---- Within limit ----

    @Test
    void shouldAllowWithinLimit() throws Exception {
        OpenScoutProperties.Quota quota = quota(true, 5, 60);
        OpenScoutProperties.Security security = security(false);
        RateLimitService rateLimitService = new RateLimitService(5, 60);
        QuotaFilter filter = new QuotaFilter(quota, security, rateLimitService);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = post("/api/agent/ask");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
            assertThat(response.getStatus()).as("request %d should pass", i + 1).isEqualTo(200);
        }
    }

    // ---- Exceeded ----

    @Test
    void shouldReturn429WhenExceeded() throws Exception {
        OpenScoutProperties.Quota quota = quota(true, 2, 60);
        OpenScoutProperties.Security security = security(false);
        RateLimitService rateLimitService = new RateLimitService(2, 60);
        QuotaFilter filter = new QuotaFilter(quota, security, rateLimitService);

        // consume 2 requests
        filter.doFilterInternal(post("/api/agent/ask"), new MockHttpServletResponse(), chain);
        filter.doFilterInternal(post("/api/agent/ask"), new MockHttpServletResponse(), chain);

        // 3rd should be rejected
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(post("/api/agent/ask"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("QUOTA_EXCEEDED");
    }

    // ---- Different keys isolated ----

    @Test
    void shouldIsolateByQuotaKey() throws Exception {
        OpenScoutProperties.Quota quota = quota(true, 1, 60);
        OpenScoutProperties.Security security = security(true, "key-a", API_KEY_HEADER);
        RateLimitService rateLimitService = new RateLimitService(1, 60);
        QuotaFilter filter = new QuotaFilter(quota, security, rateLimitService);

        // exhaust "key-a" user
        MockHttpServletRequest reqA = post("/api/agent/ask");
        reqA.addHeader(API_KEY_HEADER, "key-a");
        filter.doFilterInternal(reqA, new MockHttpServletResponse(), chain);

        // key-a should be rejected
        MockHttpServletResponse respA = new MockHttpServletResponse();
        filter.doFilterInternal(postWithKey("/api/agent/ask", "key-a"), respA, chain);
        assertThat(respA.getStatus()).isEqualTo(429);

        // key-b should still pass
        MockHttpServletResponse respB = new MockHttpServletResponse();
        filter.doFilterInternal(postWithKey("/api/agent/ask", "key-b"), respB, chain);
        assertThat(respB.getStatus()).isEqualTo(200);
    }

    // ---- Runs endpoint is also protected ----

    @Test
    void shouldProtectRunsEndpoint() throws Exception {
        OpenScoutProperties.Quota quota = quota(true, 1, 60);
        OpenScoutProperties.Security security = security(false);
        RateLimitService rateLimitService = new RateLimitService(1, 60);
        QuotaFilter filter = new QuotaFilter(quota, security, rateLimitService);

        filter.doFilterInternal(post("/api/agent/runs"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(post("/api/agent/runs"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    // ---- 429 body should not leak internal state ----

    @Test
    void shouldNotLeakInternalStateIn429() throws Exception {
        OpenScoutProperties.Quota quota = quota(true, 1, 60);
        OpenScoutProperties.Security security = security(false);
        RateLimitService rateLimitService = new RateLimitService(1, 60);
        QuotaFilter filter = new QuotaFilter(quota, security, rateLimitService);

        filter.doFilterInternal(post("/api/agent/ask"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(post("/api/agent/ask"), response, chain);

        String body = response.getContentAsString();
        assertThat(body).doesNotContain("window");
        assertThat(body).doesNotContain("count");
        assertThat(body).doesNotContain("remain");
    }

    // ---- helpers ----

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private MockHttpServletRequest postWithKey(String path, String key) {
        MockHttpServletRequest request = post(path);
        request.addHeader(API_KEY_HEADER, key);
        return request;
    }

    private static OpenScoutProperties.Quota quota(boolean enabled, int maxRequests, int windowSeconds) {
        OpenScoutProperties.Quota q = new OpenScoutProperties.Quota();
        q.setEnabled(enabled);
        q.setMaxRequestsPerWindow(maxRequests);
        q.setWindowSeconds(windowSeconds);
        return q;
    }

    private static OpenScoutProperties.Security security(boolean enabled) {
        return security(enabled, "", API_KEY_HEADER);
    }

    private static OpenScoutProperties.Security security(boolean enabled, String apiKey, String headerName) {
        OpenScoutProperties.Security s = new OpenScoutProperties.Security();
        s.setEnabled(enabled);
        s.setApiKey(apiKey);
        s.setHeaderName(headerName);
        return s;
    }
}
