package com.xcloud.metadata.model;

public record ColumnLineage(
        String targetTable,
        String targetColumn,
        String sourceTable,
        String sourceColumn,
        String procedureId,
        String qualifiedProcedureName,
        String sourceFile,
        int line
) {
}
