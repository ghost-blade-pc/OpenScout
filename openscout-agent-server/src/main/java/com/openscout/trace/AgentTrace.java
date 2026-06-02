package com.openscout.trace;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentTrace {

    private Long id;
    private Long userId;
    private final String traceId;
    private final String userQuestion;
    private final Instant startedAt;
    private final List<TraceToolCall> toolCalls = new CopyOnWriteArrayList<>();
    private String scoreSummary;
    private String finalAnswer;
    private long latencyMs;
    private String status = "RUNNING";
    private String errorMessage;

    public AgentTrace(String traceId, String userQuestion, Instant startedAt) {
        this.traceId = traceId;
        this.userQuestion = userQuestion;
        this.startedAt = startedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getUserQuestion() {
        return userQuestion;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public List<TraceToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getScoreSummary() {
        return scoreSummary;
    }

    public void setScoreSummary(String scoreSummary) {
        this.scoreSummary = scoreSummary;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
