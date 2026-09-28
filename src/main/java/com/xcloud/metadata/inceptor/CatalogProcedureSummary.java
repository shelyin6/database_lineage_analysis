package com.xcloud.metadata.inceptor;

/** One row of the database procedure list (metadata only, no source text). */
public record CatalogProcedureSummary(
        String databaseName,
        String procedureName,
        String qualifiedName,
        String parameters,
        String ownerName,
        String ownerType,
        String createTime,
        Integer sourceLength
) {

    static CatalogProcedureSummary of(InceptorProcedureRow row) {
        return new CatalogProcedureSummary(
                row.databaseName(),
                row.procedureName(),
                row.qualifiedName(),
                row.parameters(),
                row.ownerName(),
                row.ownerType(),
                row.createTime(),
                row.fullTextLength());
    }
}
