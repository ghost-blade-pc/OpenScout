package com.openscout.trace;

import com.openscout.config.OpenScoutProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceServiceTest {

    @Test
    void sanitizeShouldRedactSensitiveValuesAndTruncateLargeText() {
        OpenScoutProperties properties = new OpenScoutProperties();
        properties.getTrace().setMaxSummaryLength(40);
        TraceService traceService = new TraceService(properties);

        String sanitized = traceService.sanitize("Authorization: Bearer abc token=secret-value and a very long body");

        assertThat(sanitized).contains("<redacted>");
        assertThat(sanitized).doesNotContain("secret-value");
        assertThat(sanitized).endsWith("...<truncated>");
    }
}
