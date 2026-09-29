package com.xcloud.metadata.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xcloud.metadata.inceptor.CatalogProcedureSummary;
import com.xcloud.metadata.inceptor.InceptorCatalogIndex;
import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.security.AuthUser;
import com.xcloud.metadata.security.AuthenticationSession;
import com.xcloud.metadata.service.MetadataService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End to end check of the linkage over HTTP: what the database index holds has to show up in the
 * ordinary 存储过程 / 表 endpoints, which is what the original viewer renders.
 *
 * <p>No database is involved: the index is populated directly (exactly what
 * {@code POST /api/catalog/refresh} and a parsed procedure do at runtime), and the assertions read
 * the same JSON the UI reads.
 */
@SpringBootTest(properties = {
        "metadata.sql-directory=${java.io.tmpdir}/xcloud-it-sql",
        "metadata.annotation-database=${java.io.tmpdir}/xcloud-it-metadata.sqlite",
        "metadata.inceptor.enabled=false",
        "metadata.inceptor.index-file=${java.io.tmpdir}/xcloud-it-inceptor-index.json"
})
@AutoConfigureMockMvc
class DatabaseCatalogueApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InceptorCatalogIndex catalogIndex;

    @Autowired
    private MetadataService metadataService;

    @BeforeEach
    void seedDatabaseCatalogue() throws Exception {
        // A local .sql file keeps the file based flow realistic; the database index is added on top.
        Path sqlDirectory = Path.of(System.getProperty("java.io.tmpdir"), "xcloud-it-sql");
        Files.createDirectories(sqlDirectory);
        Files.writeString(sqlDirectory.resolve("local_table.sql"), """
                CREATE TABLE ALMP.LOCAL_TABLE (
                    ID BIGINT COMMENT '标识'
                );
                """);
        catalogIndex.clearAll();
        catalogIndex.replaceCatalogue(List.of(
                new CatalogProcedureSummary("ads", "p_parsed", "ads.p_parsed", "string", "root", "USER",
                        "2025-08-28 15:31:12.0", null),
                new CatalogProcedureSummary("ads", "p_pending", "ads.p_pending", "string", "root", "USER",
                        "2025-07-16 11:12:47.0", null)));
        catalogIndex.putParsed(new ProcedureMetadata(
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
                "BEGIN INSERT INTO ADS.T_OUT SELECT * FROM ADS.T_IN; END;"));
        metadataService.reload();
    }

    @Test
    void procedureListContainsDatabaseCatalogue() throws Exception {
        String body = mockMvc.perform(get("/api/procedures").session(session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(body.contains("P_PARSED"), body);
        assertTrue(body.contains("P_PENDING"), body);
        assertTrue(body.contains("尚未解析"), body);
    }

    @Test
    void targetTableKnowsItsDatabaseProcedure() throws Exception {
        mockMvc.perform(get("/api/tables/detail").param("name", "ADS.T_OUT").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetProcedures[0].qualifiedName").value("ADS.P_PARSED"));
    }

    @Test
    void summaryReportsTheDatabaseSource() throws Exception {
        mockMvc.perform(get("/api/summary").session(session()))
                .andExpect(status().isOk())
                // 2 database procedures (one parsed, one still a "尚未解析" row) ...
                .andExpect(jsonPath("$.procedureCount").value(2))
                // ... plus the local table and the two tables derived from the parsed procedure.
                .andExpect(jsonPath("$.tableCount").value(3))
                .andExpect(jsonPath("$.sourceFiles[1].kind").value("inceptor"))
                .andExpect(jsonPath("$.tablesBySchema.ADS").value(2));
    }

    @Test
    void catalogStatusIsReadableWithoutALogin() throws Exception {
        mockMvc.perform(get("/api/catalog/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index.catalogueSize").value(2))
                .andExpect(jsonPath("$.index.parsedSize").value(1))
                .andExpect(jsonPath("$.index.pendingSize").value(1));
    }

    @Test
    void databaseEndpointsNeedALogin() throws Exception {
        mockMvc.perform(get("/api/catalog/procedures"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/catalog/refresh").session(session()))
                .andExpect(status().isServiceUnavailable());
    }

    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthenticationSession.USER_ATTRIBUTE, new AuthUser("admin", "系统管理员", "ADMIN"));
        return session;
    }
}
