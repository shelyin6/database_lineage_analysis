package com.xcloud.metadata.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TableMetadata(
        String id,
        String schema,
        String name,
        String qualifiedName,
        String comment,
        String sourceFile,
        int startLine,
        int endLine,
        String engine,
        String partitionedBy,
        List<ColumnMetadata> columns,
        String rawSql
) {
    @JsonProperty
    public int columnCount() {
        return columns == null ? 0 : columns.size();
    }

    @JsonProperty
    public int commentedColumnCount() {
        if (columns == null) {
            return 0;
        }
        return (int) columns.stream().filter(column -> !blank(column.comment())).count();
    }

    public boolean hasComment() {
        return !blank(comment);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
