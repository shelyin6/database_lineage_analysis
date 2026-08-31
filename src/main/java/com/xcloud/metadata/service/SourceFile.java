package com.xcloud.metadata.service;

import java.nio.file.Path;

public record SourceFile(
        String display,
        Path path,
        String metadataPrefix
) {
    public SourceFile {
        display = display == null ? "" : display;
        path = path == null ? Path.of("") : path;
        metadataPrefix = metadataPrefix == null ? "" : metadataPrefix;
    }
}
