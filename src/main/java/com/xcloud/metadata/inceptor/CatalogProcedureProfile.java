package com.xcloud.metadata.inceptor;

import com.xcloud.metadata.model.ProcedureMetadata;

/**
 * Analysis of one procedure read from the database.
 *
 * <p>{@code analysis} is the same shape the file based flow produces (target tables, source tables,
 * parameters, header documentation, raw SQL), so the UI renders database procedures with the
 * existing detail view.
 *
 * @param fromCache     true when the source text came from the in-memory cache
 * @param elapsedMillis wall clock time of the whole call, including the version check
 * @param headerAdded   true when the stored text was only a body and the analyzer had to add a
 *                      {@code CREATE OR REPLACE PROCEDURE ... AS} header before parsing
 */
public record CatalogProcedureProfile(
        String databaseName,
        String procedureName,
        String qualifiedName,
        String ownerName,
        String ownerType,
        String createTime,
        String parameters,
        int sourceLength,
        boolean fromCache,
        boolean headerAdded,
        long elapsedMillis,
        ProcedureMetadata analysis
) {
}
