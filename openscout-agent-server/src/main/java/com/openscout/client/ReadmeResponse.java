package com.openscout.client;

public record ReadmeResponse(
        String fullName,
        String readme,
        int length,
        String source
) {
}
