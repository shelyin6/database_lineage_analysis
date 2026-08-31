package com.xcloud.metadata.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceFileSummary(
        @JsonProperty("file") String path,
        String kind,
        long sizeBytes,
        int lines,
        int objectCount
) {
}
