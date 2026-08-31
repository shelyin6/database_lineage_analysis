package com.xcloud.metadata.service;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.model.SourceFileSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFileResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void displayPathUsesConfiguredSqlDirectoryAsDisplayRoot() throws Exception {
        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(tempDir);

        SourceFileResolver resolver = new SourceFileResolver(properties);

        assertEquals("almp_table.sql", resolver.sourceFile(null, tempDir.resolve("almp_table.sql")).display());
        assertEquals("raw/ids_proc.sql", resolver.sourceFile(null, tempDir.resolve("raw/ids_proc.sql")).display());
        assertEquals("almp_table.sql", resolver.sourceFile(null, tempDir.resolve("almp_table.sql")).metadataPrefix());
        assertEquals("raw/ids_proc.sql", resolver.sourceFile(null, tempDir.resolve("raw/ids_proc.sql")).metadataPrefix());
    }

    @Test
    void sourceFileFromPathUsesConfiguredSqlDirectoryAsDisplayRoot() throws Exception {
        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(tempDir);
        SourceFileResolver resolver = new SourceFileResolver(properties);

        assertEquals("almp_table.sql", resolver.sourceFile(tempDir.resolve("almp_table.sql")).display());
    }

    @Test
    void displayPathKeepsAbsolutePathWhenOutsideConfiguredDirectory() throws Exception {
        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(tempDir.resolve("sql"));

        SourceFileResolver resolver = new SourceFileResolver(properties);
        Path outside = Files.createTempFile("external", ".sql");
        try {
            assertEquals(outside.toAbsolutePath().normalize().toString(), resolver.sourceFile(null, outside).display());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void relativeSqlDirectoryIsResolvedBesideApplicationDirectory() throws Exception {
        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(Path.of("sql"));

        Path expected = MetadataProperties.findApplicationDirectory().resolve("sql").normalize();
        assertEquals(expected, properties.effectiveSqlDirectory());
        assertTrue(expected.isAbsolute());
    }

    @Test
    void listSqlFilesOnlyIncludesDirectFilesAndStableOrder() throws Exception {
        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(tempDir);
        SourceFileResolver resolver = new SourceFileResolver(properties);

        Files.writeString(tempDir.resolve("b.sql"), "select 1");
        Files.writeString(tempDir.resolve("a.sql"), "select 1");
        Files.writeString(tempDir.resolve("note.txt"), "not sql");
        Files.createDirectory(tempDir.resolve("nested"));
        Files.writeString(tempDir.resolve("nested/c.sql"), "select 1");

        List<Path> files = resolver.listSqlFiles(tempDir);
        assertEquals(List.of(tempDir.resolve("a.sql"), tempDir.resolve("b.sql")), files);
    }

    @Test
    void sourceFileSummarySerializesWithBackwardCompatibleFileField() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new SourceFileSummary("almp_proc.sql", "procedure", 3, 1, 2)
        );
        assertEquals("{\"file\":\"almp_proc.sql\",\"kind\":\"procedure\",\"sizeBytes\":3,\"lines\":1,\"objectCount\":2}", json);
    }
}
