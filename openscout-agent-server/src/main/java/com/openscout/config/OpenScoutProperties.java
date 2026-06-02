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
    private Events events = new Events();
    private Security security = new Security();
    private Quota quota = new Quota();

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

    public Events getEvents() {
        return events;
    }

    public void setEvents(Events events) {
        this.events = events;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
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

    public static class Events {
        private boolean enabled = true;
        private int sseTimeoutSeconds = 300;
        private int bufferSize = 200;
        private int maxActiveRuns = 20;
        private int executorThreads = 4;
        private int heartbeatSeconds = 15;
        private int completedRetentionSeconds = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSseTimeoutSeconds() {
            return sseTimeoutSeconds;
        }

        public void setSseTimeoutSeconds(int sseTimeoutSeconds) {
            this.sseTimeoutSeconds = sseTimeoutSeconds;
        }

        public int getBufferSize() {
            return bufferSize;
        }

        public void setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
        }

        public int getMaxActiveRuns() {
            return maxActiveRuns;
        }

        public void setMaxActiveRuns(int maxActiveRuns) {
            this.maxActiveRuns = maxActiveRuns;
        }

        public int getExecutorThreads() {
            return executorThreads;
        }

        public void setExecutorThreads(int executorThreads) {
            this.executorThreads = executorThreads;
        }

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public void setHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
        }

        public int getCompletedRetentionSeconds() {
            return completedRetentionSeconds;
        }

        public void setCompletedRetentionSeconds(int completedRetentionSeconds) {
            this.completedRetentionSeconds = completedRetentionSeconds;
        }
    }

    public static class Security {
        /** API Key 保护开关，默认关闭保留本地 Demo */
        private boolean enabled = false;
        /** API Key 值（仅占位符，生产环境通过环境变量注入） */
        private String apiKey = "";
        /** 自定义 header 名称 */
        private String headerName = "X-OpenScout-Api-Key";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }
    }

    public static class Quota {
        /** 入站配额开关，默认关闭保留本地 Demo */
        private boolean enabled = false;
        /** 每窗口最大请求数 */
        private int maxRequestsPerWindow = 30;
        /** 窗口时长（秒） */
        private int windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequestsPerWindow() {
            return maxRequestsPerWindow;
        }

        public void setMaxRequestsPerWindow(int maxRequestsPerWindow) {
            this.maxRequestsPerWindow = maxRequestsPerWindow;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
