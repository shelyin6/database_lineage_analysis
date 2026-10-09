package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.service.MetadataService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The intranet report "a cache hit still takes ~1.2 s" came from re-writing the index file and
 * re-building the whole snapshot on every single view. Reading a procedure that is already indexed
 * with the same source text must do neither: only the cheap {@code create_time} check remains.
 */
class ProcedureProfileTimingTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE = """
            CREATE OR REPLACE PROCEDURE ads.p_one AS
            BEGIN
                INSERT INTO ADS.T_OUT SELECT * FROM ADS.T_IN;
            END;""";

    private static InceptorProcedureRow row() {
        return new InceptorProcedureRow("p_one", "string", SOURCE, "root", "USER",
                "2025-08-28 15:31:12.0", "ads", SOURCE.length());
    }

    private static ProcedureSourceCache.CacheStats cacheStats(long hits) {
        return new ProcedureSourceCache.CacheStats(true, 1, 1024, hits, 0, 0, 100);
    }

    @Test
    void unchangedCacheHitDoesNotRewriteTheIndexOrRebuildTheSnapshot() throws Exception {
        InceptorProperties properties = new InceptorProperties();
        properties.setEnabled(true);
        properties.setIndexFile(tempDir.resolve("index.json").toString());
        InceptorCatalogIndex index = new InceptorCatalogIndex(properties, new MetadataProperties());

        InceptorProcedureRepository repository = mock(InceptorProcedureRepository.class);
        when(repository.find("ads", "p_one")).thenReturn(Optional.of(row()));
        // cacheStats() is read before and after each call, so the counters have to move forward.
        when(repository.cacheStats()).thenReturn(
                cacheStats(0), cacheStats(1),
                cacheStats(1), cacheStats(2));

        MetadataService metadataService = mock(MetadataService.class);
        JdbcQueryExecutor executor = new JdbcQueryExecutor();
        try {
            ProcedureCatalogService service =
                    new ProcedureCatalogService(properties, repository, executor, index, metadataService);

            CatalogProcedureProfile first = service.profile("ads", "p_one");
            assertTrue(first.fromCache());
            assertEquals(List.of("ADS.T_OUT"), first.analysis().targetTables());
            // First read indexes the procedure, so the snapshot has to be rebuilt once.
            verify(metadataService, times(1)).reload();

            CatalogProcedureProfile second = service.profile("ads", "p_one");
            assertTrue(second.fromCache());
            assertEquals(1, index.stats().parsedSize());
            // Same source text: no index write, no snapshot rebuild, no local cost on the second view.
            verify(metadataService, times(1)).reload();
            assertEquals(0, second.snapshotMillis());
            assertEquals(0, second.indexMillis());
            assertTrue(second.databaseMillis() >= 0);
            assertTrue(second.elapsedMillis() >= second.databaseMillis());
        } finally {
            executor.close();
        }
    }
}
