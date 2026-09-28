package com.xcloud.metadata.inceptor;

/**
 * One row of {@code system.procedures_v}.
 *
 * @param fullText       procedure source text; only loaded for detail/analysis requests
 * @param fullTextLength character count reported by the database for list requests
 * @param createTime     raw value returned by the driver, kept as text to stay dialect neutral
 */
public record InceptorProcedureRow(
        String procedureName,
        String parameters,
        String fullText,
        String ownerName,
        String ownerType,
        String createTime,
        String databaseName,
        Integer fullTextLength
) {

    public String qualifiedName() {
        return databaseName == null || databaseName.isBlank()
                ? procedureName
                : databaseName + "." + procedureName;
    }

    /** Identifier used as evidence source, for example inceptor://ads.p_xxx. */
    public String sourceIdentifier() {
        return "inceptor://" + qualifiedName();
    }
}
