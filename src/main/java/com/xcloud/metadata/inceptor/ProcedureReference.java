package com.xcloud.metadata.inceptor;

/** Reference to one procedure in the metadata table: {@code database.procedure}. */
public record ProcedureReference(String databaseName, String procedureName) {
}
