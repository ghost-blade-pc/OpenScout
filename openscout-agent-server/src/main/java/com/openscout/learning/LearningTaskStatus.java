package com.openscout.learning;

import java.util.Arrays;

public enum LearningTaskStatus {
    TODO,
    DOING,
    DONE;

    public static LearningTaskStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        return Arrays.stream(values())
                .filter(status -> status.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "status must be one of TODO, DOING, DONE"));
    }
}
