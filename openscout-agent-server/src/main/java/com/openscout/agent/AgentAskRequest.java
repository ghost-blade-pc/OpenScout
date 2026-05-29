package com.openscout.agent;

public record AgentAskRequest(String question, String goal, String mode) {

    public String effectiveQuestion() {
        if (question != null && !question.isBlank()) {
            return question;
        }
        if (goal != null && !goal.isBlank()) {
            return goal;
        }
        return "";
    }
}
