package com.openscout.evaluation;

import com.openscout.config.OpenScoutProperties;

public record EvaluationEnvironment(
        boolean mockAgent,
        String collectorMode,
        boolean llmEnabled,
        boolean memoryEnabled,
        boolean reactEnabled,
        boolean verifierEnabled,
        boolean eventsEnabled,
        boolean persistenceEnabled,
        boolean githubTokenPresent
) {

    public static EvaluationEnvironment from(OpenScoutProperties properties) {
        return new EvaluationEnvironment(
                properties.isMockAgent(),
                properties.getCollectorMode(),
                properties.getLlm().isEnabled(),
                properties.getMemory().isEnabled(),
                properties.getReact().isEnabled(),
                properties.getVerifier().isEnabled(),
                properties.getEvents().isEnabled(),
                properties.getPersistence().isEnabled(),
                System.getenv("GITHUB_TOKEN") != null && !System.getenv("GITHUB_TOKEN").isBlank()
        );
    }
}
