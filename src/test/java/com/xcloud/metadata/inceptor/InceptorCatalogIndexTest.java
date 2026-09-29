package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.model.ProcedureMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The index is what links the database catalogue with the rest of the viewer: the catalogue gives the
 * 存储过程 tab its rows, the parsed entries feed the table and lineage views, and both survive a
 * restart so {@code full_text} is not read again.
 */
class InceptorCatalogIndexTest {

    @TempDir
    Path tempDir;

    private InceptorCatalogIndex newIndex(String fileName) {
        InceptorProperties properties = new InceptorProperties();
        properties.setIndexFile(tempDir.resolve(fileName).toString());
        MetadataProperties metadataProperties = new MetadataProperties();
        return new InceptorCatalogIndex(properties, metadataProperties);
    }

    private static CatalogProcedureSummary summary(String database, String name) {
        String qualified = database + "." + name;
        return new CatalogProcedureSummary(database, name, qualified, "string", "root", "USER",
                "2025-08-28 15:31:12.0", null);
    }

    private static ProcedureMetadata parsed(String database, String name) {
        String qualifiedName = database.toUpperCase() + "." + name.toUpperCase();
        return new ProcedureMetadata(
                "inceptor://" + qualifiedName,
                database.toUpperCase(),
                name.toUpperCase(),
                qualifiedName,
                "inceptor://" + qualifiedName,
                1,
                3,
                "CREATE OR REPLACE PROCEDURE " + qualifiedName + " IS",
                Map.of("存储过程功能", "示例"),
                List.of(),
                List.of("ADS.T_OUT"),
                List.of("ADS.T_IN"),
                List.of("ADS.T_OUT", "ADS.T_IN"),
                List.of(),
                "BEGIN INSERT INTO ADS.T_OUT SELECT * FROM ADS.T_IN; END;");
    }

    @Test
    void catalogueEntriesBecomeUnparsedPlaceholders() {
        InceptorCatalogIndex index = newIndex("catalogue-only.json");
        index.replaceCatalogue(List.of(summary("ads", "p_one"), summary("ads", "p_two")));

        List<ProcedureMetadata> placeholders = index.placeholderProcedures();

        assertEquals(2, placeholders.size());
        assertEquals("ADS.P_ONE", placeholders.get(0).qualifiedName());
        assertEquals("", placeholders.get(0).rawSql());
        assertEquals("inceptor://ADS.P_ONE", placeholders.get(0).sourceFile());
        assertTrue(placeholders.get(0).doc("存储过程功能").startsWith("尚未解析"));
        assertEquals("root / USER", placeholders.get(0).doc("拥有者"));
        assertEquals(2, index.stats().pendingSize());
    }

    @Test
    void parsedEntriesStopBeingPlaceholders() {
        InceptorCatalogIndex index = newIndex("parsed.json");
        index.replaceCatalogue(List.of(summary("ads", "p_one"), summary("ads", "p_two")));

        index.putParsed(parsed("ads", "p_one"));

        assertEquals(1, index.stats().parsedSize());
        assertEquals(1, index.stats().pendingSize());
        assertEquals(List.of("ADS.T_OUT"), index.parsed("ADS.P_ONE").orElseThrow().targetTables());
        assertTrue(index.placeholderProcedures().stream()
                .noneMatch(procedure -> procedure.qualifiedName().equals("ADS.P_ONE")));
    }

    @Test
    void catalogueAndParsedProceduresSurviveARestart() {
        InceptorCatalogIndex first = newIndex("restart.json");
        first.replaceCatalogue(List.of(summary("ads", "p_one")));
        first.putParsed(parsed("ads", "p_one"));

        InceptorCatalogIndex reloaded = newIndex("restart.json");
        reloaded.load();

        assertEquals(1, reloaded.stats().catalogueSize());
        assertEquals(1, reloaded.stats().parsedSize());
        ProcedureMetadata procedure = reloaded.parsed("ads.p_one").orElseThrow();
        assertEquals(List.of("ADS.T_IN"), procedure.sourceTables());
        assertTrue(procedure.rawSql().contains("INSERT INTO ADS.T_OUT"));
    }

    @Test
    void clearParsedKeepsTheCatalogue() {
        InceptorCatalogIndex index = newIndex("clear.json");
        index.replaceCatalogue(List.of(summary("ads", "p_one")));
        index.putParsed(parsed("ads", "p_one"));

        index.clearParsed();

        assertEquals(1, index.stats().catalogueSize());
        assertEquals(0, index.stats().parsedSize());
        assertEquals(1, index.placeholderProcedures().size());
    }

    @Test
    void clearAllEmptiesEverything() {
        InceptorCatalogIndex index = newIndex("clear-all.json");
        index.replaceCatalogue(List.of(summary("ads", "p_one")));
        index.putParsed(parsed("ads", "p_one"));

        index.clearAll();

        assertEquals(0, index.stats().catalogueSize());
        assertEquals(0, index.placeholderProcedures().size());
        assertFalse(index.parsed("ADS.P_ONE").isPresent());
    }

    @Test
    void indexFileIsCreatedBesideTheApplicationDirectory() {
        InceptorCatalogIndex index = newIndex("nested/dir/index.json");
        index.replaceCatalogue(List.of(summary("ads", "p_one")));

        assertTrue(Files.isRegularFile(index.indexFile()));
        assertTrue(index.indexFile().toString().endsWith("index.json"));
    }
}
