package com.xcloud.metadata.model;

public record ProcedureParameter(
        int ordinal,
        String name,
        String mode,
        String dataType,
        String comment,
        String rawDefinition
) {
}
