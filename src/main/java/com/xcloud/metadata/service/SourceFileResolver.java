package com.xcloud.metadata.service;

import com.xcloud.metadata.config.MetadataProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class SourceFileResolver {
    private final MetadataProperties properties;

    public SourceFileResolver(MetadataProperties properties) {
        this.properties = properties;
    }

    public Path sqlDirectory() {
        return properties.effectiveSqlDirectory();
    }

    public Path baseDirectory() {
        return sqlDirectory();
    }

    public String displayPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path base = baseDirectory().toAbsolutePath().normalize();
        if (normalized.startsWith(base)) {
            Path relative = base.relativize(normalized);
            return relative.toString().isEmpty() ? "." : relative.toString().replace(java.io.File.separatorChar, '/');
        }
        return normalized.toString().replace(java.io.File.separatorChar, '/');
    }

    public SourceFile sourceFile(String configured, Path path) {
        return new SourceFile(
                configuredDisplayPath(configured, path),
                path,
                configuredDisplayPath(configured, path)
        );
    }

    public SourceFile sourceFile(Path path) {
        return sourceFile(null, path);
    }

    public List<Path> listSqlFiles(Path directory) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .toList()) {
                result.add(path);
            }
        }
        result.sort(Comparator.comparing(this::displayPath, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private String configuredDisplayPath(String configured, Path path) {
        if (configured != null) {
            Path parsed = Path.of(configured);
            return parsed.isAbsolute()
                    ? path.toAbsolutePath().normalize().toString().replace(java.io.File.separatorChar, '/')
                    : configured.replace(java.io.File.separatorChar, '/');
        }
        return displayPath(path);
    }

}
