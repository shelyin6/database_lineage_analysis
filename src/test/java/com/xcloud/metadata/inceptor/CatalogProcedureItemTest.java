package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xcloud.metadata.model.ProcedureMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The 存储过程 tab renders database procedures through this shape: unparsed rows must be marked so the
 * UI can show "尚未解析" and parsed rows must carry their real numbers.
 */
class CatalogProcedureItemTest {

    private static InceptorProcedureRow row() {
        return new InceptorProcedureRow("p_demo", "data_dt string", null, "root", "USER",
                "2025-08-28 15:31:12.0", "ads", null);
    }

    @Test
    void unparsedCatalogueRowIsMarkedAndHasNoNumbers() {
        CatalogProcedureItem item = CatalogProcedureItem.of(row(), null);

        assertFalse(item.parsed());
        assertEquals("inceptor://ADS.P_DEMO", item.id());
        assertEquals("ADS", item.schema());
        assertEquals("P_DEMO", item.name());
        assertEquals(0, item.parameterCount());
        assertEquals(0, item.targetTableCount());
        assertEquals(0, item.sourceTableCount());
        assertTrue(item.description().startsWith("尚未解析"));
        assertEquals("2025-08-28 15:31:12.0", item.createdAt());
    }

    @Test
    void parsedProcedureShowsItsNumbers() {
        ProcedureMetadata parsed = new ProcedureMetadata(
                "inceptor://ADS.P_DEMO:1:ADS.P_DEMO",
                "ADS",
                "P_DEMO",
                "ADS.P_DEMO",
                "inceptor://ADS.P_DEMO",
                1,
                5,
                "CREATE OR REPLACE PROCEDURE ads.p_demo IS",
                Map.of("存储过程功能", "按日加工", "创建时间", "2025-08-28 15:31:12.0"),
                List.of(),
                List.of("ADS.T_OUT"),
                List.of("ADS.T_IN", "ADS.T_REF"),
                List.of("ADS.T_OUT", "ADS.T_IN", "ADS.T_REF"),
                List.of("ADS.P_OTHER"),
                "BEGIN NULL; END;");

        CatalogProcedureItem item = CatalogProcedureItem.of(row(), parsed);

        assertTrue(item.parsed());
        assertEquals(1, item.targetTableCount());
        assertEquals(2, item.sourceTableCount());
        assertEquals(3, item.referencedTableCount());
        assertEquals(1, item.calledProcedureCount());
        assertEquals("按日加工", item.description());
        assertEquals(List.of("ADS.T_IN", "ADS.T_REF"), item.sourceTables());
    }
}
