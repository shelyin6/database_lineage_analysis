package com.xcloud.metadata.model;

public record SourceFileSummary(
        String file,
        String kind,
        long sizeBytes,
        int lines,
        int objectCount
) {
}
