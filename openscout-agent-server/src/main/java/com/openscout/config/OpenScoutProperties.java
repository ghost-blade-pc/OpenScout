package com.openscout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openscout")
public class OpenScoutProperties {

    private String collectorBaseUrl = "http://localhost:8081";
    private boolean mockAgent = true;
    private String collectorMode = "mock";
    private Llm llm = new Llm();
    private Learning learning = new Learning();
    private Persistence persistence = new Persistence();
    private Trace trace = new Trace();
    private Memory memory = new Memory();
    private React react = new React();
    private Verifier verifier = new Verifier();

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

    public String getCollectorMode() {
        return collectorMode;
    }

    public void setCollectorMode(String collectorMode) {
        this.collectorMode = collectorMode;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Learning getLearning() {
        return learning;
    }

    public void setLearning(Learning learning) {
        this.learning = learning;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    public Trace getTrace() {
        return trace;
    }

    public void setTrace(Trace trace) {
        this.trace = trace;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public React getReact() {
        return react;
    }

    public void setReact(React react) {
        this.react = react;
    }

    public Verifier getVerifier() {
        return verifier;
    }

    public void setVerifier(Verifier verifier) {
        this.verifier = verifier;
    }

    public static class Llm {
        private boolean enabled = true;
        private int timeoutSeconds = 30;
        private int maxTokens = 2000;
        private double temperature = 0.7;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    public static class Persistence {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Learning {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
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

    public static class Memory {
        private boolean enabled = true;
        private int freshnessHours = 24;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFreshnessHours() {
            return freshnessHours;
        }

        public void setFreshnessHours(int freshnessHours) {
            this.freshnessHours = freshnessHours;
        }
    }

    public static class React {
        private boolean enabled = true;
        private int maxRounds = 1;
        private int maxFollowUpRepos = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        public int getMaxFollowUpRepos() {
            return maxFollowUpRepos;
        }

        public void setMaxFollowUpRepos(int maxFollowUpRepos) {
            this.maxFollowUpRepos = maxFollowUpRepos;
        }
    }

    public static class Verifier {
        private boolean enabled = true;
        private boolean llmEnabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLlmEnabled() {
            return llmEnabled;
        }

        public void setLlmEnabled(boolean llmEnabled) {
            this.llmEnabled = llmEnabled;
        }
    }
}
