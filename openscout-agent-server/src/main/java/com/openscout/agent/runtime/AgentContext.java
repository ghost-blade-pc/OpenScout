package com.openscout.agent.runtime;

import com.openscout.agent.GoalInterpretation;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.client.RepoSummary;
import com.openscout.learning.LearningPlanResponse;

import java.util.ArrayList;
import java.util.List;

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
}
