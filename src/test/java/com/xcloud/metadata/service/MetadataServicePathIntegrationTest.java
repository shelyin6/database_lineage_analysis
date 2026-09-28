package com.xcloud.metadata.service;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.model.SourceFileSummary;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataServicePathIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void configuredFileListsResolveRelativeEntriesUnderSqlDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve("sql"));
        Files.writeString(tempDir.resolve("sql/almp_proc.sql"), """
                CREATE OR REPLACE PROCEDURE ALMP.DEMO_PROC AS
                BEGIN
                    INSERT INTO ALMP.DEMO_TABLE SELECT * FROM ALMP.SRC_TABLE;
                END;
                """);

        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(tempDir.resolve("sql"));
        properties.setProcedureFiles(java.util.List.of("almp_proc.sql"));

        MetadataService service = new MetadataService(properties);
        service.reload();

        SourceFileSummary source = service.snapshot().sourceFiles().get(0);
        assertEquals("almp_proc.sql", source.path());
        assertEquals("procedure", source.kind());
        assertEquals(1, service.snapshot().procedures().size());
        assertEquals("DEMO_PROC", service.snapshot().procedures().get(0).name());
        assertEquals("almp_proc.sql", service.snapshot().procedures().get(0).sourceFile());
        assertEquals("ALMP", service.snapshot().procedures().get(0).schema());
    }

    @Test
    void configuredAbsolutePathsRemainAbsolute() throws Exception {
        Path directory = tempDir.resolve("outside-sql");
        Files.createDirectories(directory);
        Path file = directory.resolve("ids_proc.sql");
        Files.writeString(file, """
                CREATE OR REPLACE PROCEDURE IDS.DEMO_PROC AS
                BEGIN
                    NULL;
                END;
                """);

        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(tempDir.resolve("sql"));
        properties.setProcedureFiles(java.util.List.of(file.toString()));

        MetadataService service = new MetadataService(properties);
        service.reload();

        SourceFileSummary source = service.snapshot().sourceFiles().get(0);
        String expected = file.toAbsolutePath().normalize().toString().replace(java.io.File.separatorChar, '/');
        assertEquals(expected, source.path());
        assertEquals(expected, service.snapshot().procedures().get(0).sourceFile());
        assertEquals("IDS", service.snapshot().procedures().get(0).schema());
    }

    @Test
    void configuredRelativeSubdirectoryEntriesKeepConfiguredDisplayPath() throws Exception {
        Path directory = tempDir.resolve("sql");
        Files.createDirectories(directory.resolve("almp"));
        Files.writeString(directory.resolve("almp/proc.sql"), """
                CREATE OR REPLACE PROCEDURE ALMP.DEMO_PROC AS
                BEGIN
                    NULL;
                END;
                """);

        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(directory);
        properties.setProcedureFiles(java.util.List.of("almp/proc.sql"));

        MetadataService service = new MetadataService(properties);
        service.reload();

        SourceFileSummary source = service.snapshot().sourceFiles().get(0);
        assertEquals("almp/proc.sql", source.path());
        assertEquals("almp/proc.sql", service.snapshot().procedures().get(0).sourceFile());
        assertEquals("ALMP", service.snapshot().procedures().get(0).schema());
    }

    @Test
    void autoScanUsesRelativeDisplayPathInsideConfiguredDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve("sql"));
        Files.writeString(tempDir.resolve("sql/almp_table.sql"), """
                CREATE TABLE ALMP.DEMO_TABLE (
                    ID BIGINT COMMENT '标识'
                );
                """);

        MetadataProperties properties = new MetadataProperties();
        properties.setSqlDirectory(tempDir.resolve("sql"));

        MetadataService service = new MetadataService(properties);
        service.reload();

        SourceFileSummary source = service.snapshot().sourceFiles().get(0);
        assertEquals("almp_table.sql", source.path());
        assertEquals("almp_table.sql", service.snapshot().tables().get(0).sourceFile());
        assertEquals("ALMP", service.snapshot().tables().get(0).schema());
        assertEquals(1, service.snapshot().sourceFiles().size());
    }

    @Test
    void springContextLoadsWithConfiguredPathProperties() throws Exception {
        Files.createDirectories(tempDir.resolve("sql"));
        Files.writeString(tempDir.resolve("sql/almp_table.sql"), """
                CREATE TABLE ALMP.DEMO_TABLE (
                    ID BIGINT COMMENT '标识'
                );
                """);

        System.setProperty("metadata.sql-directory", tempDir.resolve("sql").toString());
        try {
            AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
            context.setServletContext(new MockServletContext());
            context.register(com.xcloud.metadata.SqlMetadataApplication.class);
            context.refresh();
            try {
                MetadataService service = context.getBean(MetadataService.class);
                assertEquals("almp_table.sql", service.snapshot().sourceFiles().get(0).path());
            } finally {
                context.close();
            }
        } finally {
            System.clearProperty("metadata.sql-directory");
        }
    }
}
