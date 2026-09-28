package com.xcloud.metadata.inceptor;

/**
 * Condition for a bounded batch analysis of database procedures.
 *
 * @param keyword  procedure name; fuzzy by default, exact when {@code exact} is true; blank matches
 *                 every procedure the account can see
 * @param database optional database filter such as {@code ads}
 * @param owner    optional owner filter such as {@code hive}
 * @param limit    optional row limit, capped by {@code metadata.inceptor.max-rows}
 */
public record CatalogSearchRequest(
        String keyword,
        String database,
        String owner,
        boolean exact,
        Integer limit
) {
}
