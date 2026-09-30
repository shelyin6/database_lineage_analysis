package com.xcloud.metadata.inceptor;

import com.xcloud.metadata.model.ProcedureMetadata;
import java.util.List;

/**
 * One row of the 存储过程 tab when the list comes from the database instead of from local .sql files.
 *
 * <p>Same JSON shape as the file based list item, so the UI renders both the same way. A procedure
 * that has already been read and parsed shows its real numbers (parameters, target/source tables,
 * called procedures); one that is only known by name is returned as a "尚未解析" row with zeros and
 * {@code parsed=false} - clicking it reads the source text through the cache and fills everything in.
 */
public record CatalogProcedureItem(
        String id,
        String schema,
        String name,
        String qualifiedName,
        String sourceFile,
        int startLine,
        int endLine,
        int parameterCount,
        int targetTableCount,
        int sourceTableCount,
        int referencedTableCount,
        int calledProcedureCount,
        String title,
        String description,
        String createdAt,
        List<String> targetTables,
        List<String> sourceTables,
        boolean parsed
) {

    public static CatalogProcedureItem of(InceptorProcedureRow row, ProcedureMetadata parsedProcedure) {
        ProcedureMetadata metadata = parsedProcedure != null
                ? parsedProcedure
                : InceptorCatalogIndex.placeholder(CatalogProcedureSummary.of(row));
        List<String> targetTables = metadata.targetTables() == null ? List.of() : metadata.targetTables();
        List<String> sourceTables = metadata.sourceTables() == null ? List.of() : metadata.sourceTables();
        List<String> referencedTables = metadata.referencedTables() == null ? List.of() : metadata.referencedTables();
        List<String> calledProcedures = metadata.calledProcedures() == null ? List.of() : metadata.calledProcedures();
        return new CatalogProcedureItem(
                metadata.id(),
                metadata.schema(),
                metadata.name(),
                metadata.qualifiedName(),
                metadata.sourceFile(),
                metadata.startLine(),
                metadata.endLine(),
                metadata.parameterCount(),
                targetTables.size(),
                sourceTables.size(),
                referencedTables.size(),
                calledProcedures.size(),
                metadata.doc("存储过程名称"),
                metadata.doc("存储过程功能"),
                metadata.doc("创建时间"),
                targetTables,
                sourceTables,
                parsedProcedure != null);
    }
}
