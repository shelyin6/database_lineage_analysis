package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.parser.SqlMetadataParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The database source only stores text; the same parser has to read it. Procedure bodies that are
 * stored without the {@code CREATE PROCEDURE} header are the interesting case.
 */
class DatabaseProcedureParsingTest {

    @Test
    void completeStatementsAreUsedAsIs() {
        String text = """
                CREATE OR REPLACE PROCEDURE p_demo AS
                BEGIN
                    INSERT INTO ads.t_out SELECT * FROM ads.t_in;
                END;
                """;
        assertFalse(ProcedureCatalogService.needsHeader(text));
        assertEquals(text.strip(), ProcedureCatalogService.prepareSource(text, "ads.p_demo"));
    }

    @Test
    void createProcedureGetsTheOrReplaceKeyword() {
        String prepared = ProcedureCatalogService.prepareSource(
                "CREATE PROCEDURE ads.p_demo(a string) AS BEGIN NULL; END;", "ads.p_demo");
        assertTrue(prepared.startsWith("CREATE OR REPLACE PROCEDURE ads.p_demo"));
    }

    @Test
    void bareBodiesGetAHeaderSoNothingIsLostByTheWrapper() {
        String prepared = ProcedureCatalogService.prepareSource(
                "BEGIN\n    EXECUTE IMMEDIATE insql;\nEND;", "ads.p_demo");
        assertTrue(prepared.startsWith("CREATE OR REPLACE PROCEDURE ads.p_demo AS "));
        assertTrue(prepared.contains("EXECUTE IMMEDIATE insql;"));
        // Single line header: the line numbers of the stored text stay usable as evidence.
        assertEquals(3, prepared.split("\\R", -1).length);
    }

    @Test
    void bareParameterListsKeepTheirSignature() {
        String prepared = ProcedureCatalogService.prepareSource("p_demo(a string) IS BEGIN NULL; END;", "ads.p_demo");
        assertTrue(prepared.startsWith("CREATE OR REPLACE PROCEDURE p_demo(a string) IS"));
    }

    @Test
    void parserUsesTheDatabaseSchemaForUnqualifiedNames() {
        String text = """
                CREATE OR REPLACE PROCEDURE p_demo AS
                BEGIN
                    INSERT INTO t_out SELECT * FROM t_in;
                END;
                """;

        List<ProcedureMetadata> parsed = new SqlMetadataParser()
                .parseProcedures("inceptor://crsql.p_demo", "crsql", text);

        assertEquals(1, parsed.size());
        ProcedureMetadata procedure = parsed.get(0);
        assertEquals("CRSQL", procedure.schema());
        assertEquals("P_DEMO", procedure.name());
        assertEquals("CRSQL.P_DEMO", procedure.qualifiedName());
        assertEquals(List.of("CRSQL.T_OUT"), procedure.targetTables());
        assertEquals(List.of("CRSQL.T_IN"), procedure.sourceTables());
        assertEquals("inceptor://crsql.p_demo", procedure.sourceFile());
    }

    @Test
    void fileBasedDefaultsAreUnchanged() {
        String text = """
                CREATE OR REPLACE PROCEDURE IDS.DEMO_PROC AS
                BEGIN
                    INSERT INTO DEMO_TABLE SELECT * FROM SRC_TABLE;
                END;
                """;

        List<ProcedureMetadata> parsed = new SqlMetadataParser().parseProcedures("ids_proc.sql", text);

        assertEquals(1, parsed.size());
        assertEquals("IDS", parsed.get(0).schema());
        assertEquals(List.of("IDS.DEMO_TABLE"), parsed.get(0).targetTables());
    }
}
