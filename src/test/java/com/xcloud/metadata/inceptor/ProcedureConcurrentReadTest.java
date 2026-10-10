package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.service.MetadataService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reported on the intranet: clicking an unparsed procedure twice (or clicking while the list is still
 * auto-selecting) started two 4-5 second reads of the same procedure through the dblink view, which is
 * what made the page feel stuck. Concurrent reads of one procedure must share a single result.
 */
class ProcedureConcurrentReadTest {

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

    @Test
    void twoCallersShareOneDatabaseRead() throws Exception {
        InceptorProperties properties = new InceptorProperties();
        properties.setEnabled(true);
        properties.setRequestTimeoutSeconds(10);
        properties.setIndexFile(tempDir.resolve("index.json").toString());
        InceptorCatalogIndex index = new InceptorCatalogIndex(properties, new MetadataProperties());

        InceptorProcedureRepository repository = mock(InceptorProcedureRepository.class);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger databaseCalls = new AtomicInteger();
        when(repository.find("ads", "p_one")).thenAnswer(invocation -> {
            databaseCalls.incrementAndGet();
            reading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(row());
        });
        when(repository.cacheStats())
                .thenReturn(new ProcedureSourceCache.CacheStats(true, 0, 0, 0, 0, 0, 0));

        JdbcQueryExecutor executor = new JdbcQueryExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            ProcedureCatalogService service = new ProcedureCatalogService(
                    properties, repository, executor, index, mock(MetadataService.class));

            Future<CatalogProcedureProfile> first = callers.submit(() -> service.profile("ads", "p_one"));
            assertTrue(reading.await(5, TimeUnit.SECONDS), "the first read should be running");
            Future<CatalogProcedureProfile> second = callers.submit(() -> service.profile("ads", "p_one"));
            Thread.sleep(200);
            release.countDown();

            assertEquals(List.of("ADS.T_OUT"), first.get(10, TimeUnit.SECONDS).analysis().targetTables());
            assertEquals(List.of("ADS.T_OUT"), second.get(10, TimeUnit.SECONDS).analysis().targetTables());
            assertEquals(1, databaseCalls.get(), "both callers must share one database read");
        } finally {
            callers.shutdownNow();
            executor.close();
        }
    }
}
