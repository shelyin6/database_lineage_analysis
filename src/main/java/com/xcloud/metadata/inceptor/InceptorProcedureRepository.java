package com.xcloud.metadata.inceptor;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Read-only repository for {@code system.procedures_v}.
 *
 * <p>Only SELECT statements are issued, always through prepared statements; the table name comes
 * from configuration and is validated against a strict identifier pattern so it cannot be used for
 * injection. Query text is never logged.
 */
@Repository
public class InceptorProcedureRepository {

    private static final Logger LOG = LoggerFactory.getLogger(InceptorProcedureRepository.class);

    /** Table names are limited to plain identifiers: letters, digits, underscore, dot, dollar. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.]+");

    private static final String SUMMARY_COLUMNS = "procedure_name, parameters, owner_name, owner_type,"
            + " create_time, database_name";

    private static final String DETAIL_COLUMNS = "procedure_name, parameters, full_text, owner_name,"
            + " owner_type, create_time, database_name";

    /** Names bound into one IN (...) clause; keeps the statement size bounded for large batches. */
    private static final int NAME_CHUNK_SIZE = 100;

    private final InceptorProperties properties;
    private final InceptorDriverLoader driverLoader;
    private final InceptorConnectionPool connectionPool;
    private final ProcedureSourceCache sourceCache;

    public InceptorProcedureRepository(
            InceptorProperties properties,
            InceptorDriverLoader driverLoader,
            InceptorConnectionPool connectionPool,
            ProcedureSourceCache sourceCache
    ) {
        this.properties = properties;
        this.driverLoader = driverLoader;
        this.connectionPool = connectionPool;
        this.sourceCache = sourceCache;
        String table = properties.getProcedureTable();
        if (table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalStateException("metadata.inceptor.procedure-table 配置不合法：" + table);
        }
    }

    /** Connection pool counters, surfaced through the status endpoint. */
    public InceptorConnectionPool.PoolStats poolStats() {
        return connectionPool.stats();
    }

    /** Source cache counters, surfaced through the status endpoint. */
    public ProcedureSourceCache.CacheStats cacheStats() {
        return sourceCache.stats();
    }

    public void clearCache() {
        sourceCache.clear();
    }

    /**
     * Searches procedures by name.
     *
     * @param keyword      procedure name; fuzzy match by default, exact match when {@code exact} is true
     * @param databaseName optional database filter, for example {@code ads}
     * @param ownerName    optional owner filter, for example {@code hive}
     */
    public List<InceptorProcedureRow> search(
            String keyword,
            String databaseName,
            String ownerName,
            boolean exact,
            Integer requestedLimit
    ) {
        List<Object> arguments = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (isPresent(keyword)) {
            if (exact) {
                // Exact lookups use the raw column so the engine can use its metadata index;
                // procedure names in the catalogue are lower case (p_xxx).
                conditions.add("procedure_name = ?");
                arguments.add(keyword.trim());
            } else {
                conditions.add("lower(procedure_name) LIKE lower(?)");
                arguments.add("%" + keyword.trim() + "%");
            }
        }
        if (isPresent(databaseName)) {
            conditions.add("lower(database_name) = lower(?)");
            arguments.add(databaseName.trim());
        }
        if (isPresent(ownerName)) {
            conditions.add("lower(owner_name) = lower(?)");
            arguments.add(ownerName.trim());
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(summaryColumns())
                .append(" FROM ")
                .append(properties.getProcedureTable());
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (properties.isSortResults()) {
            // ORDER BY forces the engine to sort every matching row; the UI sorts one page locally.
            sql.append(" ORDER BY database_name, procedure_name");
        }
        int limit = effectiveLimit(requestedLimit);
        sql.append(" LIMIT ").append(limit);

        List<InceptorProcedureRow> rows = new ArrayList<>();
        long startedAt = System.nanoTime();
        long[] queryMillis = new long[1];
        withConnection("查询存储过程元数据失败", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                applyTimeout(statement);
                statement.setMaxRows(limit);
                for (int index = 0; index < arguments.size(); index++) {
                    statement.setObject(index + 1, arguments.get(index));
                }
                long queryStartedAt = System.nanoTime();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(map(resultSet, false));
                    }
                }
                queryMillis[0] = elapsedMillis(queryStartedAt);
            }
            return null;
        });
        LOG.info("列表查询：rows={}，查询={}ms，总计={}ms，连接复用率={}%（keyword={}, exact={}, database={}, owner={}, orderBy={}, textLength={}）",
                rows.size(), queryMillis[0], elapsedMillis(startedAt), poolStats().reusePercent(),
                keyword, exact, databaseName, ownerName,
                properties.isSortResults(), properties.isIncludeTextLength());
        return rows;
    }

    /**
     * List projection. {@code length(full_text)} is opt-in: it forces the engine to read the LOB of
     * every matching row (measured ~0.75 s per row on a remote Inceptor).
     */
    private String summaryColumns() {
        return properties.isIncludeTextLength()
                ? SUMMARY_COLUMNS + ", length(full_text) AS full_text_length"
                : SUMMARY_COLUMNS;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** Loads one procedure including its full source text. */
    public Optional<InceptorProcedureRow> find(String databaseName, String procedureName) {
        long startedAt = System.nanoTime();
        String cacheKey = cacheKey(databaseName, procedureName);

        if (properties.isCacheEnabled()) {
            // Cheap metadata-only query first: it tells us whether the cached text is still current.
            String version = withConnection("读取存储过程版本失败",
                    connection -> queryVersion(connection, databaseName, procedureName));
            if (version == null) {
                sourceCache.invalidate(cacheKey);
                return Optional.empty();
            }
            ProcedureSourceCache.CachedProcedure cached = sourceCache.get(cacheKey, version);
            if (cached != null) {
                LOG.info("读取源码：object={}.{}，缓存命中，总计={}ms（未读取 full_text）",
                        databaseName, procedureName, elapsedMillis(startedAt));
                return Optional.of(cached.row());
            }
        }

        Optional<InceptorProcedureRow> row = withConnection("读取存储过程内容失败",
                connection -> queryDetail(connection, databaseName, procedureName));
        row.ifPresent(value -> sourceCache.put(cacheKey, value.createTime(), value));
        LOG.info("读取源码：object={}.{}，缓存未命中，总计={}ms，found={}",
                databaseName, procedureName, elapsedMillis(startedAt), row.isPresent());
        return row;
    }

    private Optional<InceptorProcedureRow> queryDetail(
            Connection connection,
            String databaseName,
            String procedureName
    ) throws SQLException {
        String sql = "SELECT " + DETAIL_COLUMNS + " FROM " + properties.getProcedureTable()
                + " WHERE lower(database_name) = lower(?) AND lower(procedure_name) = lower(?) LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            statement.setObject(1, databaseName);
            statement.setObject(2, procedureName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet, true)) : Optional.empty();
            }
        }
    }

    /** {@code create_time} acts as the cache version marker for one procedure. */
    private String queryVersion(Connection connection, String databaseName, String procedureName)
            throws SQLException {
        String sql = "SELECT create_time FROM " + properties.getProcedureTable()
                + " WHERE lower(database_name) = lower(?) AND lower(procedure_name) = lower(?) LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            statement.setObject(1, databaseName);
            statement.setObject(2, procedureName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readString(resultSet, "create_time") : null;
            }
        }
    }

    /** Version markers for a batch of names, one cheap query per (database, chunk). */
    private Map<String, String> queryVersions(Connection connection, String database, List<String> names)
            throws SQLException {
        boolean hasDatabase = !database.isEmpty();
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        String sql = "SELECT procedure_name, create_time FROM " + properties.getProcedureTable()
                + " WHERE " + (hasDatabase ? "lower(database_name) = lower(?) AND " : "")
                + "lower(procedure_name) IN (" + placeholders + ")";
        Map<String, String> versions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            int index = 1;
            if (hasDatabase) {
                statement.setObject(index++, database);
            }
            for (String name : names) {
                statement.setObject(index++, name.toLowerCase(Locale.ROOT));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    versions.put(
                            readString(resultSet, "procedure_name").toLowerCase(Locale.ROOT),
                            readString(resultSet, "create_time"));
                }
            }
        }
        return versions;
    }

    /** Lightweight connectivity probe used by the status endpoint. */
    public void verifyConnection() {
        long startedAt = System.nanoTime();
        withConnection("连接或读取元数据表失败", connection -> {
            try (Statement statement = connection.createStatement()) {
                applyTimeout(statement);
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT 1 AS ping FROM " + properties.getProcedureTable() + " LIMIT 1")) {
                    resultSet.next();
                }
            }
            return null;
        });
        LOG.info("连通性自检：总计={}ms，连接复用率={}%",
                elapsedMillis(startedAt), poolStats().reusePercent());
    }

    /**
     * Loads several procedures including their full source text.
     *
     * <p>Keys are grouped by database and chunked so a batch analysis of dozens of procedures runs a
     * handful of statements instead of one query per procedure.
     *
     * @return rows keyed by lower-cased {@code database.procedure}
     */
    public Map<String, InceptorProcedureRow> findAll(List<ProcedureReference> references) {
        Map<String, InceptorProcedureRow> found = new LinkedHashMap<>();
        long startedAt = System.nanoTime();
        if (references == null || references.isEmpty()) {
            return found;
        }

        Map<String, LinkedHashSet<String>> byDatabase = groupByDatabase(references);
        List<ProcedureReference> missing = new ArrayList<>();

        if (properties.isCacheEnabled()) {
            // One cheap metadata query per (database, chunk) yields the current version markers.
            Map<String, Map<String, String>> versions = new LinkedHashMap<>();
            withConnection("读取存储过程版本失败", connection -> {
                for (Map.Entry<String, LinkedHashSet<String>> entry : byDatabase.entrySet()) {
                    List<String> names = new ArrayList<>(entry.getValue());
                    for (int start = 0; start < names.size(); start += NAME_CHUNK_SIZE) {
                        List<String> chunk = names.subList(start, Math.min(names.size(), start + NAME_CHUNK_SIZE));
                        versions.computeIfAbsent(entry.getKey(), key -> new LinkedHashMap<>())
                                .putAll(queryVersions(connection, entry.getKey(), chunk));
                    }
                }
                return null;
            });
            for (ProcedureReference reference : references) {
                if (reference == null || !isPresent(reference.procedureName())) {
                    continue;
                }
                String database = isPresent(reference.databaseName()) ? reference.databaseName().trim() : "";
                String name = reference.procedureName().trim();
                String version = versions.getOrDefault(database, Map.of()).get(name.toLowerCase(Locale.ROOT));
                ProcedureSourceCache.CachedProcedure cached = version == null
                        ? null
                        : sourceCache.get(cacheKey(database, name), version);
                if (cached == null) {
                    missing.add(reference);
                } else {
                    found.put(cacheKey(database, name), cached.row());
                }
            }
        } else {
            missing.addAll(references);
        }

        int cacheHits = found.size();
        if (!missing.isEmpty()) {
            Map<String, LinkedHashSet<String>> missingByDatabase = groupByDatabase(missing);
            withConnection("批量读取存储过程内容失败", connection -> {
                for (Map.Entry<String, LinkedHashSet<String>> entry : missingByDatabase.entrySet()) {
                    List<String> names = new ArrayList<>(entry.getValue());
                    for (int start = 0; start < names.size(); start += NAME_CHUNK_SIZE) {
                        List<String> chunk = names.subList(start, Math.min(names.size(), start + NAME_CHUNK_SIZE));
                        loadChunk(connection, entry.getKey(), chunk, found);
                    }
                }
                return null;
            });
        }
        LOG.info("批量读取源码：请求={} 个，缓存命中={} 个，实取={} 个，数据库分组={}，总计={}ms（full_text 不打印）",
                references.size(), cacheHits, missing.size(), byDatabase.size(), elapsedMillis(startedAt));
        return found;
    }

    private Map<String, LinkedHashSet<String>> groupByDatabase(List<ProcedureReference> references) {
        Map<String, LinkedHashSet<String>> byDatabase = new LinkedHashMap<>();
        for (ProcedureReference reference : references) {
            if (reference == null || !isPresent(reference.procedureName())) {
                continue;
            }
            String database = isPresent(reference.databaseName()) ? reference.databaseName().trim() : "";
            byDatabase.computeIfAbsent(database, key -> new LinkedHashSet<>())
                    .add(reference.procedureName().trim());
        }
        return byDatabase;
    }

    /** Stable cache key: lower-cased {@code database.procedure}. */
    private static String cacheKey(String databaseName, String procedureName) {
        String database = databaseName == null ? "" : databaseName.trim();
        String name = procedureName == null ? "" : procedureName.trim();
        return (database + "." + name).toLowerCase(Locale.ROOT);
    }

    private void loadChunk(
            Connection connection,
            String database,
            List<String> names,
            Map<String, InceptorProcedureRow> found
    ) throws SQLException {
        boolean hasDatabase = !database.isEmpty();
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        String sql = "SELECT " + DETAIL_COLUMNS + " FROM " + properties.getProcedureTable()
                + " WHERE " + (hasDatabase ? "lower(database_name) = lower(?) AND " : "")
                + "lower(procedure_name) IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            int index = 1;
            if (hasDatabase) {
                statement.setObject(index++, database);
            }
            for (String name : names) {
                statement.setObject(index++, name.toLowerCase(Locale.ROOT));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    InceptorProcedureRow row = map(resultSet, true);
                    found.put(row.qualifiedName().toLowerCase(Locale.ROOT), row);
                    sourceCache.put(cacheKey(row.databaseName(), row.procedureName()), row.createTime(), row);
                }
            }
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Runs one unit of work on a pooled connection.
     *
     * <p>Opening an Inceptor session costs 0.1~0.6 s, so connections are reused; they are validated
     * before reuse and dropped when a query fails, so a broken session never poisons the pool.
     */
    private <T> T withConnection(String failureMessage, ConnectionWork<T> work) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new InceptorUnavailableException("未配置 metadata.inceptor.url");
        }
        Connection connection = null;
        try {
            connection = connectionPool.borrow();
            T result = work.run(connection);
            connectionPool.release(connection);
            return result;
        } catch (SQLException exception) {
            connectionPool.discard(connection);
            throw new InceptorUnavailableException(failureMessage + "：" + exception.getMessage(), exception);
        }
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private void applyTimeout(Statement statement) {
        try {
            statement.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
        } catch (SQLException ignored) {
            // Some drivers do not support query timeouts; the request timeout still applies.
        }
    }

    private int effectiveLimit(Integer requestedLimit) {
        int max = Math.max(1, properties.getMaxRows());
        if (requestedLimit == null || requestedLimit <= 0) {
            return max;
        }
        return Math.min(requestedLimit, max);
    }

    /** True when a JDBC driver could be loaded (external driver directory first). */
    public boolean isDriverAvailable() {
        return driverLoader.isAvailable();
    }

    /** Resolved driver class name, or null when no driver is available. */
    public String driverClassName() {
        return driverLoader.driverClassName();
    }

    /** Human readable driver description including how it was loaded. */
    public String driverDescription() {
        return driverLoader.description();
    }

    /** Directory scanned for external driver jars. */
    public String driverDirectory() {
        return driverLoader.driverDirectory();
    }

    private InceptorProcedureRow map(ResultSet resultSet, boolean withFullText) throws SQLException {
        String fullText = withFullText ? readString(resultSet, "full_text") : null;
        // Explicit branches: a mixed int/Integer ternary would unbox and NPE when the column is
        // absent (list queries no longer select length(full_text) by default).
        Integer length;
        if (withFullText) {
            length = fullText == null ? Integer.valueOf(0) : Integer.valueOf(fullText.length());
        } else {
            length = readInteger(resultSet, "full_text_length");
        }
        return new InceptorProcedureRow(
                readString(resultSet, "procedure_name"),
                readString(resultSet, "parameters"),
                fullText,
                readString(resultSet, "owner_name"),
                readString(resultSet, "owner_type"),
                readString(resultSet, "create_time"),
                readString(resultSet, "database_name"),
                length);
    }

    private String readString(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE));
            } catch (SQLException exception) {
                return value.toString();
            }
        }
        return value instanceof String text ? text : String.valueOf(value);
    }

    private Integer readInteger(ResultSet resultSet, String column) {
        try {
            Object value = resultSet.getObject(column);
            if (value == null) {
                return null;
            }
            return value instanceof Number number
                    ? number.intValue()
                    : Integer.valueOf(String.valueOf(value).trim());
        } catch (SQLException | NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Sanitized endpoint description for logs and the status endpoint (never the full url with user). */
    public String sanitizedEndpoint() {
        String url = properties.getUrl();
        if (url == null || url.isBlank()) {
            return "(未配置)";
        }
        return url.replaceAll("(?i)(password|user|userName|principal)=[^;&/]*", "$1=***")
                .toUpperCase(Locale.ROOT);
    }
}
