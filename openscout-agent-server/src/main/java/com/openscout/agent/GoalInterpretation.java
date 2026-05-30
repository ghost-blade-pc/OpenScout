package com.openscout.agent;

/**
 * LLM 目标解释结果。
 */
public record GoalInterpretation(
        String keyword,
        String language,
        String domain
) {
}
