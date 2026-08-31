package com.xcloud.metadata.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "metadata")
public class MetadataProperties {
    private Path sqlDirectory;
    private Path annotationDatabase = Path.of("metadata.sqlite");
    private List<String> tableFiles = new ArrayList<>();
    private List<String> procedureFiles = new ArrayList<>();

    public synchronized Path applicationDirectory() {
        return findApplicationDirectory();
    }

    public static Path findApplicationDirectory() {
        try {
            URI uri = MetadataProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path location = Path.of(uri).toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(location)) {
                Path parent = location.getParent();
                if (parent != null) {
                    return parent;
                }
            }
        } catch (Exception ignored) {
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public Path effectiveSqlDirectory() {
        return resolveApplicationPath(sqlDirectory == null ? Path.of(".") : sqlDirectory);
    }

    public Path effectiveAnnotationDatabase() {
        return resolveApplicationPath(annotationDatabase);
    }

    private Path resolveApplicationPath(Path path) {
        Path value = path == null ? Path.of(".") : path;
        return value.isAbsolute()
                ? value.normalize()
                : applicationDirectory().resolve(value).normalize();
    }

    public Path getSqlDirectory() {
        return sqlDirectory;
    }

    public void setSqlDirectory(Path sqlDirectory) {
        this.sqlDirectory = sqlDirectory;
    }

    public Path getAnnotationDatabase() {
        return annotationDatabase;
    }

    public void setAnnotationDatabase(Path annotationDatabase) {
        this.annotationDatabase = annotationDatabase;
    }

    public List<String> getTableFiles() {
        return tableFiles;
    }

    public void setTableFiles(List<String> tableFiles) {
        this.tableFiles = tableFiles == null ? new ArrayList<>() : tableFiles;
    }

    public List<String> getProcedureFiles() {
        return procedureFiles;
    }

    public void setProcedureFiles(List<String> procedureFiles) {
        this.procedureFiles = procedureFiles == null ? new ArrayList<>() : procedureFiles;
    }

}
