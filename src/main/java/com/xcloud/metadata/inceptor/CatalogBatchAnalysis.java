package com.xcloud.metadata.inceptor;

import com.xcloud.metadata.model.ProcedureMetadata;
import java.util.List;

/**
 * Result of analysing a bounded batch of procedures matched by a name/database/owner condition.
 *
 * <p>{@code matched} is how many procedures the condition matched, {@code analyzed} how many were
 * actually read and parsed; {@code truncated} is true when the condition matched more than
 * {@code metadata.inceptor.max-analyze-procedures}. {@code targetTables}/{@code sourceTables} are
 * the de-duplicated unions over the analysed procedures.
 */
public record CatalogBatchAnalysis(
        int matched,
        int analyzed,
        boolean truncated,
        boolean fromCache,
        long elapsedMillis,
        List<String> skipped,
        List<String> targetTables,
        List<String> sourceTables,
        List<ProcedureMetadata> procedures
) {
}
