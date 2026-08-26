package com.xcloud.metadata.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record ProcedureMetadata(
        String id,
        String schema,
        String name,
        String qualifiedName,
        String sourceFile,
        int startLine,
        int endLine,
        String signature,
        Map<String, String> documentation,
        List<ProcedureParameter> parameters,
        List<String> targetTables,
        List<String> sourceTables,
        List<String> referencedTables,
        List<String> calledProcedures,
        String rawSql
) {
    public ProcedureMetadata withCalledProcedures(List<String> calledProcedures) {
        return new ProcedureMetadata(
                id,
                schema,
                name,
                qualifiedName,
                sourceFile,
                startLine,
                endLine,
                signature,
                documentation,
                parameters,
                targetTables,
                sourceTables,
                referencedTables,
                calledProcedures,
                rawSql
        );
    }

    @JsonProperty
    public int parameterCount() {
        return parameters == null ? 0 : parameters.size();
    }

    public String doc(String key) {
        return documentation == null ? "" : documentation.getOrDefault(key, "");
    }
}
