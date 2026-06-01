package com.openscout.agent.react;

public record EvidenceGap(
        String fullName,
        EvidenceGapType gapType,
        String reason,
        String action,
        int priority
) {
}
