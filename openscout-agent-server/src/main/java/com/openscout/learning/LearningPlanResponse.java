package com.openscout.learning;

import java.util.List;

public record LearningPlanResponse(
        Long goalId,
        String goal,
        String targetStack,
        int durationDays,
        boolean persisted,
        List<LearningTaskResponse> tasks
) {
    public LearningPlanResponse withPersistence(Long goalId, boolean persisted, List<LearningTaskResponse> tasks) {
        return new LearningPlanResponse(goalId, goal, targetStack, durationDays, persisted, tasks);
    }
}
