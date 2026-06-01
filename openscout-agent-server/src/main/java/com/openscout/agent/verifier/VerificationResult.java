package com.openscout.agent.verifier;

import java.util.ArrayList;
import java.util.List;

public class VerificationResult {

    private boolean scoreIntegrityOk = true;
    private boolean evidenceClaimsOk = true;
    private boolean learningPlanOk = true;
    private final List<VerificationIssue> issues = new ArrayList<>();

    public boolean isScoreIntegrityOk() {
        return scoreIntegrityOk;
    }

    public void setScoreIntegrityOk(boolean scoreIntegrityOk) {
        this.scoreIntegrityOk = scoreIntegrityOk;
    }

    public boolean isEvidenceClaimsOk() {
        return evidenceClaimsOk;
    }

    public void setEvidenceClaimsOk(boolean evidenceClaimsOk) {
        this.evidenceClaimsOk = evidenceClaimsOk;
    }

    public boolean isLearningPlanOk() {
        return learningPlanOk;
    }

    public void setLearningPlanOk(boolean learningPlanOk) {
        this.learningPlanOk = learningPlanOk;
    }

    public List<VerificationIssue> getIssues() {
        return issues;
    }

    public void addIssue(VerificationIssue issue) {
        if (issue != null) {
            issues.add(issue);
        }
    }

    public boolean allOk() {
        return scoreIntegrityOk && evidenceClaimsOk && learningPlanOk;
    }

    public String summary() {
        if (allOk() && issues.isEmpty()) {
            return "allOk=true";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("scoreIntegrity=").append(scoreIntegrityOk ? "ok" : "issues");
        sb.append(" evidenceClaims=").append(evidenceClaimsOk ? "ok" : "issues");
        sb.append(" learningPlan=").append(learningPlanOk ? "ok" : "issues");
        sb.append(" totalIssues=").append(issues.size());
        return sb.toString();
    }
}
