package com.xcloud.metadata.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.inceptor.CatalogProcedureSummary;
import com.xcloud.metadata.inceptor.InceptorCatalogIndex;
import com.xcloud.metadata.inceptor.InceptorProperties;
import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.model.TableMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Database procedures have to end up in the same snapshot as the ones parsed from local .sql files,
 * otherwise the 存储过程 / 表 / 血缘 views stay empty on an intranet machine without SQL files.
 */
class DatabaseCatalogueLinkTest {

    @TempDir
    Path tempDir;

    private static CatalogProcedureSummary summary(String database, String name) {
        String qualified = database + "." + name;
        return new CatalogProcedureSummary(database, name, qualified, "string", "root", "USER",
                "2025-08-28 15:31:12.0", null);
    }

    private static ProcedureMetadata parsedProcedure() {
        return new ProcedureMetadata(
                "inceptor://ADS.P_PARSED",
                "ADS",
                "P_PARSED",
                "ADS.P_PARSED",
                "inceptor://ADS.P_PARSED",
                1,
                4,
                "CREATE OR REPLACE PROCEDURE ADS.P_PARSED IS",
                Map.of("存储过程功能", "按日加工账户模型"),
                List.of(),
                List.of("ADS.T_OUT"),
                List.of("ADS.T_IN"),
                List.of("ADS.T_OUT", "ADS.T_IN"),
                List.of(),
                "BEGIN INSERT INTO ADS.T_OUT SELECT * FROM ADS.T_IN; END;");
    }

    @Test
    void databaseProceduresAndTablesAreMergedIntoTheFileSnapshot() throws Exception {
        Path sqlDirectory = tempDir.resolve("sql");
        Files.createDirectories(sqlDirectory);
        Files.writeString(sqlDirectory.resolve("local_table.sql"), """
                CREATE TABLE ALMP.LOCAL_TABLE (
                    ID BIGINT COMMENT '标识'
                );
                """);

        MetadataProperties metadataProperties = new MetadataProperties();
        metadataProperties.setSqlDirectory(sqlDirectory);
        InceptorProperties inceptorProperties = new InceptorProperties();
        inceptorProperties.setIndexFile(tempDir.resolve("index.json").toString());
        InceptorCatalogIndex index = new InceptorCatalogIndex(inceptorProperties, metadataProperties);
        index.replaceCatalogue(List.of(summary("ads", "p_parsed"), summary("ads", "p_pending")));
        index.putParsed(parsedProcedure());

        MetadataService service = new MetadataService(metadataProperties, index);
        service.reload();

        // 存储过程 tab: the parsed procedure plus a placeholder for the one that is only known by name.
        assertEquals(2, service.snapshot().procedures().size());
        ProcedureMetadata pending = service.snapshot().procedures().stream()
                .filter(procedure -> procedure.qualifiedName().equals("ADS.P_PENDING"))
                .findFirst()
                .orElseThrow();
        assertEquals("", pending.rawSql());
        assertTrue(pending.sourceFile().startsWith("inceptor://"));

        // 表 tab: the local table plus thin entries derived from the database procedure.
        List<String> tables = service.snapshot().tables().stream().map(TableMetadata::qualifiedName).toList();
        assertTrue(tables.contains("ALMP.LOCAL_TABLE"), tables.toString());
        assertTrue(tables.contains("ADS.T_OUT"), tables.toString());
        assertTrue(tables.contains("ADS.T_IN"), tables.toString());
        assertTrue(service.snapshot().tables().stream()
                .filter(table -> table.qualifiedName().equals("ADS.T_OUT"))
                .findFirst()
                .orElseThrow()
                .sourceFile()
                .equals("inceptor://ADS.P_PARSED"));

        // 关系索引: the procedure now appears as the writer of its target table / reader of its source.
        assertTrue(service.snapshot().proceduresByTargetTable().containsKey("ADS.T_OUT"));
        assertTrue(service.snapshot().proceduresBySourceTable().containsKey("ADS.T_IN"));
        assertEquals("ADS.P_PARSED",
                service.snapshot().proceduresByTargetTable().get("ADS.T_OUT").get(0).qualifiedName());
        assertEquals("inceptor", service.snapshot().sourceFiles().get(1).kind());
    }

    @Test
    void fileOnlyModeIsUnchanged() throws Exception {
        Path sqlDirectory = tempDir.resolve("sql");
        Files.createDirectories(sqlDirectory);
        Files.writeString(sqlDirectory.resolve("local_proc.sql"), """
                CREATE OR REPLACE PROCEDURE IDS.DEMO_PROC AS
                BEGIN
                    INSERT INTO DEMO_TABLE SELECT * FROM SRC_TABLE;
                END;
                """);

        MetadataProperties metadataProperties = new MetadataProperties();
        metadataProperties.setSqlDirectory(sqlDirectory);

        MetadataService service = new MetadataService(metadataProperties);
        service.reload();

        assertEquals(1, service.snapshot().procedures().size());
        assertEquals(1, service.snapshot().sourceFiles().size());
        assertEquals("IDS.DEMO_PROC", service.snapshot().procedures().get(0).qualifiedName());
    }

    @Test
    void intranetDeploymentWithoutSqlFilesStillListsDatabaseProcedures() throws Exception {
        Path emptySqlDirectory = tempDir.resolve("empty-sql");
        Files.createDirectories(emptySqlDirectory);

        MetadataProperties metadataProperties = new MetadataProperties();
        metadataProperties.setSqlDirectory(emptySqlDirectory);
        InceptorProperties inceptorProperties = new InceptorProperties();
        inceptorProperties.setIndexFile(tempDir.resolve("empty-index.json").toString());
        InceptorCatalogIndex index = new InceptorCatalogIndex(inceptorProperties, metadataProperties);
        index.replaceCatalogue(List.of(summary("ads", "p_pending")));

        MetadataService service = new MetadataService(metadataProperties, index);
        // No .sql file anywhere: reload must not throw, otherwise a catalogue refresh could never
        // reach the 存储过程 tab.
        service.reload();

        assertEquals(1, service.snapshot().procedures().size());
        assertEquals("ADS.P_PENDING", service.snapshot().procedures().get(0).qualifiedName());
        assertEquals(0, service.snapshot().tables().size());
        assertEquals(1, service.snapshot().sourceFiles().size());
    }
}
