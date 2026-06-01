package com.openscout.agent.verifier;

public final class VerifierUtils {

    private VerifierUtils() {
    }

    public static String extractShortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        int slash = fullName.lastIndexOf('/');
        return slash >= 0 ? fullName.substring(slash + 1) : fullName;
    }

    public static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }
}
