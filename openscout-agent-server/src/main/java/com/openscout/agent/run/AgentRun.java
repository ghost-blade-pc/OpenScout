package com.openscout.agent.run;

import com.openscout.agent.ProjectRecommendation;
import com.openscout.learning.LearningPlanResponse;

import java.time.Instant;
import java.util.List;

public class AgentRun {

    private final String runId;
    private final String traceId;
    private final String question;
    private final Instant createdAt;
    private volatile AgentRunStatus status = AgentRunStatus.QUEUED;
    private volatile String answer;
    private volatile List<ProjectRecommendation> recommendations = List.of();
    private volatile LearningPlanResponse learningPlan;
    private volatile String errorSummary;
    private volatile long latencyMs;
    private volatile Instant completedAt;

    public AgentRun(String runId, String traceId, String question, Instant createdAt) {
        this.runId = runId;
        this.traceId = traceId;
        this.question = question;
        this.createdAt = createdAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getQuestion() {
        return question;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public String getAnswer() {
        return answer;
    }

    public List<ProjectRecommendation> getRecommendations() {
        return recommendations;
    }

    public LearningPlanResponse getLearningPlan() {
        return learningPlan;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void markRunning() {
        this.status = AgentRunStatus.RUNNING;
    }

    public void markSucceeded(String answer,
                              List<ProjectRecommendation> recommendations,
                              LearningPlanResponse learningPlan,
                              long latencyMs) {
        this.answer = answer;
        this.recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        this.learningPlan = learningPlan;
        this.latencyMs = latencyMs;
        this.status = AgentRunStatus.SUCCEEDED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorSummary, long latencyMs) {
        this.errorSummary = errorSummary;
        this.latencyMs = latencyMs;
        this.status = AgentRunStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public boolean isActive() {
        return status == AgentRunStatus.QUEUED || status == AgentRunStatus.RUNNING;
    }
}
