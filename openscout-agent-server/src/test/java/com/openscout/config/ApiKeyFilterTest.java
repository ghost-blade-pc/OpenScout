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

class ApiKeyFilterTest {

    private static final String HEADER = "X-OpenScout-Api-Key";
    private static final String VALID_KEY = "test-key-123";

    private FilterChain chain;

    @BeforeEach
    void setUp() {
        chain = mock(FilterChain.class);
    }

    // ---- Disabled ----

    @Test
    void shouldPassThroughWhenDisabled() throws Exception {
        OpenScoutProperties.Security security = security(false, VALID_KEY, HEADER);
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---- Enabled: missing key ----

    @Test
    void shouldReturn401WhenKeyMissing() throws Exception {
        OpenScoutProperties.Security security = security(true, VALID_KEY, HEADER);
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    // ---- Enabled: invalid key ----

    @Test
    void shouldReturn401WhenKeyInvalid() throws Exception {
        OpenScoutProperties.Security security = security(true, VALID_KEY, HEADER);
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        request.addHeader(HEADER, "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    // ---- Enabled: valid key ----

    @Test
    void shouldPassThroughWhenKeyValid() throws Exception {
        OpenScoutProperties.Security security = security(true, VALID_KEY, HEADER);
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        request.addHeader(HEADER, VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---- Health endpoint exempt ----

    @Test
    void shouldExemptHealthWhenEnabled() throws Exception {
        OpenScoutProperties.Security security = security(true, VALID_KEY, HEADER);
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---- Error response must not leak key ----

    @Test
    void shouldNotLeakKeyInErrorResponse() throws Exception {
        OpenScoutProperties.Security security = security(true, VALID_KEY, HEADER);
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        String body = response.getContentAsString();
        assertThat(body).doesNotContain(VALID_KEY);
    }

    // ---- Custom header name ----

    @Test
    void shouldUseCustomHeaderName() throws Exception {
        OpenScoutProperties.Security security = security(true, VALID_KEY, "X-Custom-Key");
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        request.addHeader("X-Custom-Key", VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldRejectWhenCustomHeaderMissing() throws Exception {
        OpenScoutProperties.Security security = security(true, VALID_KEY, "X-Custom-Key");
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        request.addHeader(HEADER, VALID_KEY); // wrong header name
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ---- Null key edge case (defense against relaxed binding) ----

    @Test
    void shouldReturn401WhenApiKeyIsNull() throws Exception {
        OpenScoutProperties.Security security = security(true, null, HEADER);
        ApiKeyFilter filter = new ApiKeyFilter(security, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/ask");
        request.addHeader(HEADER, "some-key"); // any key should fail against null expected
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        // Must not NPE; should return 401
        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ---- helper ----

    private static OpenScoutProperties.Security security(boolean enabled, String apiKey, String headerName) {
        OpenScoutProperties.Security security = new OpenScoutProperties.Security();
        security.setEnabled(enabled);
        security.setApiKey(apiKey);
        security.setHeaderName(headerName);
        return security;
    }
}
