package com.xcloud.metadata.inceptor;

/**
 * Sanitized view of the database source for the UI and for the deployment self-check.
 *
 * <p>Never contains credentials: the endpoint is masked and the driver information only names the
 * class and the directory that was scanned.
 */
public record CatalogStatus(
        boolean enabled,
        boolean driverAvailable,
        String driverClassName,
        String driverDescription,
        String driverDirectory,
        String endpoint,
        String procedureTable,
        int maxRows,
        int maxAnalyzeProcedures,
        int requestTimeoutSeconds,
        int poolSize,
        int connectionIdleSeconds,
        Pool pool,
        Cache cache
) {

    public record Pool(
            int open,
            int idle,
            int maxSize,
            long created,
            long borrowed,
            long reused,
            long discarded,
            int reusePercent
    ) {
        static Pool of(InceptorConnectionPool.PoolStats stats) {
            return new Pool(
                    stats.open(),
                    stats.idle(),
                    stats.maxSize(),
                    stats.created(),
                    stats.borrowed(),
                    stats.reused(),
                    stats.discarded(),
                    stats.reusePercent());
        }
    }

    public record Cache(
            boolean enabled,
            int entries,
            long bytes,
            long hits,
            long misses,
            long evictions,
            int hitPercent
    ) {
        static Cache of(ProcedureSourceCache.CacheStats stats) {
            return new Cache(
                    stats.enabled(),
                    stats.entries(),
                    stats.bytes(),
                    stats.hits(),
                    stats.misses(),
                    stats.evictions(),
                    stats.hitPercent());
        }
    }
}
