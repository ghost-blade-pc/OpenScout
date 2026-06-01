package com.openscout.agent.verifier;

public class VerificationIssue {

    private final String checkType;
    private final String severity;
    private final String description;
    private final String detail;

    private VerificationIssue(String checkType, String severity, String description, String detail) {
        this.checkType = checkType;
        this.severity = severity;
        this.description = description;
        this.detail = detail;
    }

    public static VerificationIssue warning(String checkType, String description, String detail) {
        return new VerificationIssue(checkType, "WARNING", description, detail);
    }

    public static VerificationIssue error(String checkType, String description, String detail) {
        return new VerificationIssue(checkType, "ERROR", description, detail);
    }

    public String getCheckType() {
        return checkType;
    }

    public String getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "[" + severity + "][" + checkType + "] " + description + (detail != null ? " detail=" + detail : "");
    }
}
