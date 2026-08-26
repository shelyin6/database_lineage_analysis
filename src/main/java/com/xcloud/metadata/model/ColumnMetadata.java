package com.xcloud.metadata.model;

public record ColumnMetadata(
        int ordinal,
        String name,
        String dataType,
        String typeName,
        String typeArguments,
        boolean nullable,
        String defaultValue,
        String comment,
        int line,
        String rawDefinition
) {
}
