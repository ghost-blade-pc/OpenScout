package com.openscout.learning;

public record LearningTaskResponse(
        Long id,
        int dayNo,
        String title,
        String detail,
        String expectedOutput,
        String status
) {
    public LearningTaskResponse withIdAndStatus(Long id, String status) {
        return new LearningTaskResponse(id, dayNo, title, detail, expectedOutput, status);
    }
}
