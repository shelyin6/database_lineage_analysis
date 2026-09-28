package com.xcloud.metadata.inceptor;

import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.parser.SqlMetadataParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bridges the Inceptor metadata table with the existing analyzer.
 *
 * <p>The database only provides source text; parsing, dependency extraction and the detail view stay
 * exactly the same as in the file based flow. Every database call goes through
 * {@link JdbcQueryExecutor} (request timeout), {@link InceptorConnectionPool} (connection reuse) and
 * {@link ProcedureSourceCache} (source text reuse) so a slow remote metadata table cannot hang the
 * UI or pay the {@code full_text} cost twice.
 */
@Service
public class ProcedureCatalogService {

    private static final Logger LOG = LoggerFactory.getLogger(ProcedureCatalogService.class);

    private static final Pattern CREATE_OR_REPLACE = Pattern.compile("^\\s*create\\s+or\\s+replace\\s+procedure\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_ONLY = Pattern.compile("^\\s*create\\s+procedure\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNATURE_ONLY = Pattern.compile("^\\s*[A-Za-z0-9_$\"]+\\s*\\(|^\\s*\\(");

    private final InceptorProperties properties;
    private final InceptorProcedureRepository repository;
    private final JdbcQueryExecutor queryExecutor;
    private final SqlMetadataParser parser = new SqlMetadataParser();

    public ProcedureCatalogService(
            InceptorProperties properties,
            InceptorProcedureRepository repository,
            JdbcQueryExecutor queryExecutor
    ) {
        this.properties = properties;
        this.repository = repository;
        this.queryExecutor = queryExecutor;
    }

    public CatalogStatus status() {
        return new CatalogStatus(
                properties.isEnabled(),
                repository.isDriverAvailable(),
                repository.driverClassName(),
                repository.driverDescription(),
                repository.driverDirectory(),
                repository.sanitizedEndpoint(),
                properties.getProcedureTable(),
                properties.getMaxRows(),
                properties.getMaxAnalyzeProcedures(),
                properties.getRequestTimeoutSeconds(),
                properties.getPoolSize(),
                properties.getConnectionIdleSeconds(),
                CatalogStatus.Pool.of(repository.poolStats()),
                CatalogStatus.Cache.of(repository.cacheStats()));
    }

    /** Drops every cached source text; the next preview/analysis reads from the database again. */
    public CatalogStatus clearCache() {
        repository.clearCache();
        LOG.info("源码缓存已清空（下一次查看源码将重新读取 full_text）");
        return status();
    }

    /** Verifies connectivity; only available when the feature is enabled. */
    public CatalogStatus verifyConnection() {
        requireEnabled();
        queryExecutor.executeVoid("连通性自检", requestTimeoutMillis(), repository::verifyConnection);
        return status();
    }

    public List<CatalogProcedureSummary> search(
            String keyword,
            String databaseName,
            String ownerName,
            boolean exact,
            Integer limit
    ) {
        requireEnabled();
        List<InceptorProcedureRow> rows = queryExecutor.execute(
                "查询存储过程列表",
                requestTimeoutMillis(),
                () -> repository.search(keyword, databaseName, ownerName, exact, limit));
        return rows.stream().map(CatalogProcedureSummary::of).toList();
    }

    /**
     * Reads one procedure (source text through the cache) and parses it with the standard analyzer.
     */
    public CatalogProcedureProfile profile(String databaseName, String procedureName) {
        requireEnabled();
        long startedAt = System.nanoTime();
        long hitsBefore = repository.cacheStats().hits();

        InceptorProcedureRow row = queryExecutor.execute(
                "读取存储过程内容",
                requestTimeoutMillis(),
                () -> repository.find(databaseName, procedureName))
                .orElseThrow(() -> new ProcedureNotFoundException(
                        "未找到存储过程 " + databaseName + "." + procedureName));

        boolean fromCache = repository.cacheStats().hits() > hitsBefore;
        String text = row.fullText() == null ? "" : row.fullText();
        if (text.isBlank()) {
            throw new ProcedureNotFoundException("存储过程 " + row.qualifiedName()
                    + " 的 full_text 为空（可能未同步或被加密），无法分析");
        }
        ParsedSource parsed = parse(row, text);
        return new CatalogProcedureProfile(
                row.databaseName(),
                row.procedureName(),
                row.qualifiedName(),
                row.ownerName(),
                row.ownerType(),
                row.createTime(),
                row.parameters(),
                text.length(),
                fromCache,
                parsed.headerAdded(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                parsed.metadata());
    }

    /**
     * Analyses a bounded batch of procedures matched by a condition: "walk the catalogue and collect
     * the tables these procedures touch".
     */
    public CatalogBatchAnalysis analyzeByCondition(CatalogSearchRequest request) {
        requireEnabled();
        int maxAnalyze = Math.max(1, properties.getMaxAnalyzeProcedures());
        int searchLimit = Math.min(
                effectiveLimit(request == null ? null : request.limit()),
                maxAnalyze + 1);
        long startedAt = System.nanoTime();
        long hitsBefore = repository.cacheStats().hits();

        List<InceptorProcedureRow> matched = queryExecutor.execute(
                "查询待分析的存储过程",
                requestTimeoutMillis(),
                () -> repository.search(
                        request == null ? null : request.keyword(),
                        request == null ? null : request.database(),
                        request == null ? null : request.owner(),
                        request != null && request.exact(),
                        searchLimit));
        if (matched.isEmpty()) {
            throw new ProcedureNotFoundException("未找到匹配的存储过程，请调整名称、数据库或拥有者条件");
        }

        boolean truncated = matched.size() > maxAnalyze;
        List<InceptorProcedureRow> selected = truncated ? matched.subList(0, maxAnalyze) : matched;
        Map<String, InceptorProcedureRow> rows = queryExecutor.execute(
                "批量读取存储过程源码",
                requestTimeoutMillis(),
                () -> repository.findAll(selected.stream()
                        .map(row -> new ProcedureReference(row.databaseName(), row.procedureName()))
                        .toList()));

        List<ProcedureMetadata> procedures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        TreeSet<String> targetTables = new TreeSet<>();
        TreeSet<String> sourceTables = new TreeSet<>();
        for (InceptorProcedureRow row : selected) {
            InceptorProcedureRow loaded = rows.getOrDefault(
                    row.qualifiedName().toLowerCase(java.util.Locale.ROOT), row);
            String text = loaded.fullText();
            if (text == null || text.isBlank()) {
                skipped.add(row.qualifiedName() + "（full_text 为空，可能未同步或已加密）");
                continue;
            }
            ProcedureMetadata metadata = parse(loaded, text).metadata();
            procedures.add(metadata);
            targetTables.addAll(metadata.targetTables());
            sourceTables.addAll(metadata.sourceTables());
        }
        if (procedures.isEmpty()) {
            throw new ProcedureNotFoundException("未获取到可用源码：" + String.join("；", skipped));
        }

        boolean fromCache = repository.cacheStats().hits() > hitsBefore;
        LOG.info("批量分析：匹配={} 个，分析={} 个，截断={}，缓存命中={}，总计={}ms（full_text 不打印）",
                matched.size(), procedures.size(), truncated, fromCache,
                (System.nanoTime() - startedAt) / 1_000_000L);
        return new CatalogBatchAnalysis(
                matched.size(),
                procedures.size(),
                truncated,
                fromCache,
                (System.nanoTime() - startedAt) / 1_000_000L,
                List.copyOf(skipped),
                List.copyOf(targetTables),
                List.copyOf(sourceTables),
                List.copyOf(procedures));
    }

    private ParsedSource parse(InceptorProcedureRow row, String text) {
        String sourceFile = row.sourceIdentifier();
        List<ProcedureMetadata> parsed = parser.parseProcedures(sourceFile, row.databaseName(), text);
        if (!parsed.isEmpty()) {
            return new ParsedSource(parsed.get(0), false);
        }
        // Some catalogues store only the body, or a bare parameter list. Add the smallest header that
        // makes the text a complete statement and analyse that, instead of reporting "no dependency".
        String prepared = prepareSource(text, row.qualifiedName());
        parsed = parser.parseProcedures(sourceFile, row.databaseName(), prepared);
        if (!parsed.isEmpty()) {
            return new ParsedSource(parsed.get(0), true);
        }
        throw new InceptorUnavailableException("无法解析 " + row.qualifiedName()
                + " 的源码：未找到 CREATE [OR REPLACE] PROCEDURE 语句");
    }

    /**
     * Normalizes stored source text so the shared parser can read it.
     *
     * <ul>
     *   <li>{@code CREATE OR REPLACE PROCEDURE ...} is used as is.</li>
     *   <li>{@code CREATE PROCEDURE ...} gets the {@code OR REPLACE} keyword.</li>
     *   <li>A bare signature such as {@code p_x(a string) IS ...} gets the {@code CREATE OR REPLACE
     *       PROCEDURE} prefix.</li>
     *   <li>Anything else (a bare body) gets a one line header so the line numbering of the stored
     *       text is preserved.</li>
     * </ul>
     */
    static String prepareSource(String text, String qualifiedName) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (CREATE_OR_REPLACE.matcher(trimmed).find()) {
            return trimmed;
        }
        if (CREATE_ONLY.matcher(trimmed).find()) {
            return trimmed.replaceFirst("(?is)^\\s*create\\s+procedure", "CREATE OR REPLACE PROCEDURE");
        }
        if (SIGNATURE_ONLY.matcher(trimmed).find()) {
            return "CREATE OR REPLACE PROCEDURE " + trimmed;
        }
        return "CREATE OR REPLACE PROCEDURE " + qualifiedName + " AS " + trimmed;
    }

    private long requestTimeoutMillis() {
        return Math.max(1, properties.getRequestTimeoutSeconds()) * 1000L;
    }

    private int effectiveLimit(Integer requestedLimit) {
        int max = Math.max(1, properties.getMaxRows());
        if (requestedLimit == null || requestedLimit <= 0) {
            return max;
        }
        return Math.min(requestedLimit, max);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new InceptorUnavailableException(
                    "数据库接入未启用：请在 application.yml 中设置 metadata.inceptor.enabled=true 并配置连接信息");
        }
    }

    private record ParsedSource(ProcedureMetadata metadata, boolean headerAdded) {
    }

    /** Diagnostics helper used by the tests: the prepared statement header decision. */
    static boolean needsHeader(String text) {
        String trimmed = text == null ? "" : text.strip();
        return !trimmed.isEmpty()
                && !CREATE_OR_REPLACE.matcher(trimmed).find()
                && !CREATE_ONLY.matcher(trimmed).find()
                && !SIGNATURE_ONLY.matcher(trimmed).find();
    }
}
