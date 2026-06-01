package com.openscout.agent.runtime;

import com.openscout.agent.GoalInterpretation;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.client.RepoSummary;
import com.openscout.learning.LearningPlanResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AgentContext {

    private final String userGoal;
    private final AgentRuntimeMode mode;
    private GoalInterpretation interpretation;
    private List<RepoSummary> repos = new ArrayList<>();
    private List<ProjectRecommendation> recommendations = new ArrayList<>();
    private LearningPlanResponse learningPlan;
    private String answer;
    private boolean memoryHit;
    private int memoryRepoCount;
    private final Map<String, ReadmeFailureObservation> readmeFailureObservations = new HashMap<>();

    public AgentContext(String userGoal, AgentRuntimeMode mode) {
        this.userGoal = userGoal;
        this.mode = mode;
    }

    public String getUserGoal() {
        return userGoal;
    }

    public AgentRuntimeMode getMode() {
        return mode;
    }

    public GoalInterpretation getInterpretation() {
        return interpretation;
    }

    public void setInterpretation(GoalInterpretation interpretation) {
        this.interpretation = interpretation;
    }

    public String keyword() {
        return interpretation == null || interpretation.keyword() == null ? userGoal : interpretation.keyword();
    }

    public List<RepoSummary> getRepos() {
        return repos;
    }

    public void setRepos(List<RepoSummary> repos) {
        this.repos = repos == null ? List.of() : repos;
    }

    public List<ProjectRecommendation> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<ProjectRecommendation> recommendations) {
        this.recommendations = recommendations == null ? List.of() : recommendations;
    }

    public LearningPlanResponse getLearningPlan() {
        return learningPlan;
    }

    public void setLearningPlan(LearningPlanResponse learningPlan) {
        this.learningPlan = learningPlan;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public boolean isMemoryHit() {
        return memoryHit;
    }

    public void setMemoryHit(boolean memoryHit) {
        this.memoryHit = memoryHit;
    }

    public int getMemoryRepoCount() {
        return memoryRepoCount;
    }

    public void setMemoryRepoCount(int memoryRepoCount) {
        this.memoryRepoCount = memoryRepoCount;
    }

    public void recordReadmeFailure(String fullName, String status) {
        recordReadmeFailure(fullName, status, 0);
    }

    public void recordReadmeFailure(String fullName, String status, int retryAfterSeconds) {
        if (fullName == null || fullName.isBlank() || status == null || status.isBlank()) {
            return;
        }
        readmeFailureObservations.put(fullName,
                new ReadmeFailureObservation(status, Math.max(0, retryAfterSeconds)));
    }

    public Optional<String> readmeFailureStatus(String fullName) {
        return readmeFailureObservation(fullName).map(ReadmeFailureObservation::status);
    }

    public Optional<ReadmeFailureObservation> readmeFailureObservation(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(readmeFailureObservations.get(fullName));
    }

    public void clearReadmeFailure(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return;
        }
        readmeFailureObservations.remove(fullName);
    }

    public record ReadmeFailureObservation(String status, int retryAfterSeconds) {
    }
}
