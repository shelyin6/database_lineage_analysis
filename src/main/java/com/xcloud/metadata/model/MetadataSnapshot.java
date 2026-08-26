package com.xcloud.metadata.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MetadataSnapshot(
        Instant loadedAt,
        List<TableMetadata> tables,
        List<ProcedureMetadata> procedures,
        List<SourceFileSummary> sourceFiles,
        Map<String, List<ProcedureMetadata>> proceduresByReferencedTable,
        Map<String, List<ProcedureMetadata>> proceduresByTargetTable,
        Map<String, List<ProcedureMetadata>> proceduresBySourceTable,
        Map<String, List<ColumnLineage>> columnLineageByTargetTable
) {
    public int columnCount() {
        return tables.stream().mapToInt(TableMetadata::columnCount).sum();
    }
}
