package com.openscout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openscout")
public class OpenScoutProperties {

    private String collectorBaseUrl = "http://localhost:8081";
    private boolean mockAgent = true;
    private Trace trace = new Trace();

    public String getCollectorBaseUrl() {
        return collectorBaseUrl;
    }

    public void setCollectorBaseUrl(String collectorBaseUrl) {
        this.collectorBaseUrl = collectorBaseUrl;
    }

    public boolean isMockAgent() {
        return mockAgent;
    }

    public void setMockAgent(boolean mockAgent) {
        this.mockAgent = mockAgent;
    }

    public Trace getTrace() {
        return trace;
    }

    public void setTrace(Trace trace) {
        this.trace = trace;
    }

    public static class Trace {
        private int maxSummaryLength = 800;

        public int getMaxSummaryLength() {
            return maxSummaryLength;
        }

        public void setMaxSummaryLength(int maxSummaryLength) {
            this.maxSummaryLength = maxSummaryLength;
        }
    }
}
