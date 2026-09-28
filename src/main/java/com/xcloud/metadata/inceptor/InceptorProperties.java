package com.xcloud.metadata.inceptor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Read-only access to the Inceptor procedure metadata table.
 *
 * <p>Disabled by default ({@code metadata.inceptor.enabled=false}): the viewer keeps working on
 * local SQL files only until an operator explicitly enables the database source and provides a
 * read-only account.
 */
@ConfigurationProperties(prefix = "metadata.inceptor")
public class InceptorProperties {

    /** Master switch. Default false so a deployment never connects to a database by accident. */
    private boolean enabled = false;

    /** JDBC url, for example {@code jdbc:inceptor2://10.0.0.9:10000/system}. */
    private String url = "";

    /** Driver class supplied by the external Inceptor client package; loaded reflectively. */
    private String driverClassName = "org.apache.hive.jdbc.HiveDriver";

    /**
     * Directory holding the external JDBC driver jar(s). Relative paths are resolved beside the
     * executable jar, so an offline deployment finds {@code <jar-dir>/lib}.
     */
    private String driverDir = "lib";

    private String username = "";

    /** Prefer an environment variable placeholder, for example {@code ${OSDA_INCEPTOR_PASSWORD}}. */
    private String password = "";

    /** Metadata table holding procedure definitions. */
    private String procedureTable = "system.procedures_v";

    private int connectTimeoutSeconds = 10;

    private int queryTimeoutSeconds = 30;

    /**
     * Overall guard for one catalogue operation (connect + query + mapping).
     *
     * <p>{@code Statement.setQueryTimeout} is only a hint and Hive-family drivers commonly ignore
     * it, so the service also abandons the call after this many seconds and reports a timeout
     * instead of letting the HTTP request hang.
     */
    private int requestTimeoutSeconds = 60;

    /**
     * Include {@code length(full_text)} in list results (default false).
     *
     * <p>Reading the LOB length forces the engine to touch every matching row's source text, which
     * measured around 0.75 s per row on a remote Inceptor: a five row list query took ~4 s with it
     * and a fraction of that without. Turn it on only when the length column is really needed.
     */
    private boolean includeTextLength = false;

    /**
     * Let the database sort list results (default false).
     *
     * <p>ORDER BY forces the engine to sort every matching row before applying LIMIT; the UI sorts
     * the returned page locally instead, which is free at this scale.
     */
    private boolean sortResults = false;

    /** Maximum number of JDBC connections kept open and reused between requests. */
    private int poolSize = 2;

    /** An idle connection older than this is closed instead of reused. */
    private int connectionIdleSeconds = 300;

    /** How long a request waits for a free connection before failing. */
    private int connectionWaitMillis = 5000;

    /**
     * Cache procedure source text in memory (default true).
     *
     * <p>Reading {@code full_text} through the dblink backed view costs ~4-5 s per query, while a
     * metadata-only query costs ~0.2 s. A repeated preview/analysis of the same procedure should
     * not pay that cost twice.
     */
    private boolean cacheEnabled = true;

    private int cacheMaxEntries = 200;

    private long cacheMaxBytes = 64L * 1024 * 1024;

    /** Entries older than this are refreshed even when the version marker did not change. */
    private int cacheTtlSeconds = 1800;

    /** Re-check {@code create_time} before serving a cached source (cheap metadata query). */
    private boolean cacheVerifyVersion = true;

    /** Hard upper bound for one search request. */
    private int maxRows = 200;

    /** Hard upper bound for one batch analysis request. */
    private int maxAnalyzeProcedures = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getDriverDir() {
        return driverDir;
    }

    public void setDriverDir(String driverDir) {
        this.driverDir = driverDir;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getProcedureTable() {
        return procedureTable;
    }

    public void setProcedureTable(String procedureTable) {
        this.procedureTable = procedureTable;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public boolean isIncludeTextLength() {
        return includeTextLength;
    }

    public void setIncludeTextLength(boolean includeTextLength) {
        this.includeTextLength = includeTextLength;
    }

    public boolean isSortResults() {
        return sortResults;
    }

    public void setSortResults(boolean sortResults) {
        this.sortResults = sortResults;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public int getConnectionIdleSeconds() {
        return connectionIdleSeconds;
    }

    public void setConnectionIdleSeconds(int connectionIdleSeconds) {
        this.connectionIdleSeconds = connectionIdleSeconds;
    }

    public int getConnectionWaitMillis() {
        return connectionWaitMillis;
    }

    public void setConnectionWaitMillis(int connectionWaitMillis) {
        this.connectionWaitMillis = connectionWaitMillis;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    public long getCacheMaxBytes() {
        return cacheMaxBytes;
    }

    public void setCacheMaxBytes(long cacheMaxBytes) {
        this.cacheMaxBytes = cacheMaxBytes;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public boolean isCacheVerifyVersion() {
        return cacheVerifyVersion;
    }

    public void setCacheVerifyVersion(boolean cacheVerifyVersion) {
        this.cacheVerifyVersion = cacheVerifyVersion;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getMaxAnalyzeProcedures() {
        return maxAnalyzeProcedures;
    }

    public void setMaxAnalyzeProcedures(int maxAnalyzeProcedures) {
        this.maxAnalyzeProcedures = maxAnalyzeProcedures;
    }
}
