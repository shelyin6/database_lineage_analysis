package com.xcloud.metadata.inceptor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.model.ProcedureMetadata;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local read model that links the database procedure catalogue with the rest of the viewer.
 *
 * <p>Two things are kept here and both are persisted to {@code inceptor-index.json}:
 *
 * <ol>
 *   <li><b>过程目录</b> - every {@code database.procedure} name from
 *       {@code system.procedures_v}. This is a metadata-only query (no {@code full_text}), so the
 *       whole catalogue loads in about a second and can be refreshed at start up. Each entry becomes
 *       a "尚未解析" row in the 存储过程 tab.</li>
 *   <li><b>已解析过程</b> - the procedures whose source text has been read and parsed. Parsing one
 *       procedure (from the 数据库 tab, from its detail page, or from a bounded batch index) stores
 *       the result here, which is what feeds the 表 / 目标表 / 来源表 / 血缘 views.</li>
 * </ol>
 *
 * <p>Reading {@code full_text} costs about 4-5 seconds per procedure, so nothing here ever reads the
 * whole catalogue at once: names come from the cheap query, source text is read per procedure on
 * demand and kept (in memory + on disk) so a restart does not pay for it again.
 */
@Component
public class InceptorCatalogIndex {

    private static final Logger LOG = LoggerFactory.getLogger(InceptorCatalogIndex.class);

    /** Marker prefix of every procedure that comes from the database source. */
    public static final String SOURCE_PREFIX = "inceptor://";

    private final InceptorProperties properties;
    private final MetadataProperties metadataProperties;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object lock = new Object();
    private final Map<String, CatalogProcedureSummary> catalogue = new LinkedHashMap<>();
    private final Map<String, ProcedureMetadata> parsed = new LinkedHashMap<>();

    private volatile Instant catalogueLoadedAt;
    private volatile Instant parsedUpdatedAt;
    private volatile String lastError = "";

    public InceptorCatalogIndex(InceptorProperties properties, MetadataProperties metadataProperties) {
        this.properties = properties;
        this.metadataProperties = metadataProperties;
    }

    @PostConstruct
    public void load() {
        Path file = indexFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            PersistedState state = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    PersistedState.class);
            synchronized (lock) {
                catalogue.clear();
                parsed.clear();
                if (state.catalogue() != null) {
                    for (CatalogProcedureSummary row : state.catalogue()) {
                        if (row != null && row.qualifiedName() != null) {
                            catalogue.put(key(row.qualifiedName()), row);
                        }
                    }
                }
                if (state.procedures() != null) {
                    for (ProcedureMetadata procedure : state.procedures()) {
                        if (procedure != null && procedure.qualifiedName() != null) {
                            parsed.put(key(procedure.qualifiedName()), procedure);
                        }
                    }
                }
                catalogueLoadedAt = parseInstant(state.catalogueLoadedAt());
                parsedUpdatedAt = parseInstant(state.parsedUpdatedAt());
            }
            LOG.info("已加载数据库过程索引 {}：目录 {} 条，已解析 {} 条", file, catalogue.size(), parsed.size());
        } catch (IOException | RuntimeException exception) {
            lastError = "读取索引文件失败：" + exception.getMessage();
            LOG.warn("读取数据库过程索引失败 {}：{}（将从空索引开始）", file, exception.getMessage());
        }
    }

    public Path indexFile() {
        String configured = properties.getIndexFile();
        Path path = Path.of(configured == null || configured.isBlank() ? "inceptor-index.json" : configured);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return metadataProperties.applicationDirectory().resolve(path).normalize();
    }

    public List<CatalogProcedureSummary> catalogue() {
        synchronized (lock) {
            return List.copyOf(catalogue.values());
        }
    }

    public List<ProcedureMetadata> parsedProcedures() {
        synchronized (lock) {
            return List.copyOf(parsed.values());
        }
    }

    public Optional<ProcedureMetadata> parsed(String qualifiedName) {
        synchronized (lock) {
            return Optional.ofNullable(parsed.get(key(qualifiedName)));
        }
    }

    public boolean isParsed(String qualifiedName) {
        synchronized (lock) {
            return parsed.containsKey(key(qualifiedName));
        }
    }

    /** Replaces the catalogue with a fresh listing. Parsed procedures are kept. */
    public void replaceCatalogue(List<CatalogProcedureSummary> rows) {
        synchronized (lock) {
            catalogue.clear();
            if (rows != null) {
                for (CatalogProcedureSummary row : rows) {
                    if (row != null && row.qualifiedName() != null) {
                        catalogue.put(key(row.qualifiedName()), row);
                    }
                }
            }
            catalogueLoadedAt = Instant.now();
            lastError = "";
        }
        save();
    }

    /**
     * Stores a parsed procedure (source text + dependencies) and drops the "尚未解析" placeholder.
     *
     * @return true when the stored entry actually changed. That is the signal for the caller to
     *         rebuild the metadata snapshot: re-opening an already parsed procedure with the same
     *         source text costs neither a disk write nor a snapshot rebuild.
     */
    public boolean putParsed(ProcedureMetadata procedure) {
        if (procedure == null || procedure.qualifiedName() == null) {
            return false;
        }
        synchronized (lock) {
            String entryKey = key(procedure.qualifiedName());
            ProcedureMetadata existing = parsed.get(entryKey);
            if (sameSource(existing, procedure)) {
                return false;
            }
            parsed.put(entryKey, procedure);
            parsedUpdatedAt = Instant.now();
            evictOldestParsed();
        }
        save();
        return true;
    }

    /**
     * Batch variant of {@link #putParsed}: stores many procedures with a single disk write, instead of
     * rewriting the whole index file once per procedure.
     *
     * @return how many entries actually changed
     */
    public int putParsedAll(List<ProcedureMetadata> procedures) {
        if (procedures == null || procedures.isEmpty()) {
            return 0;
        }
        int changed = 0;
        synchronized (lock) {
            for (ProcedureMetadata procedure : procedures) {
                if (procedure == null || procedure.qualifiedName() == null) {
                    continue;
                }
                String entryKey = key(procedure.qualifiedName());
                if (sameSource(parsed.get(entryKey), procedure)) {
                    continue;
                }
                parsed.put(entryKey, procedure);
                changed++;
            }
            if (changed > 0) {
                parsedUpdatedAt = Instant.now();
                evictOldestParsed();
            }
        }
        if (changed > 0) {
            save();
        }
        return changed;
    }

    /** Identical source text means identical analysis: everything else is derived from it. */
    private static boolean sameSource(ProcedureMetadata existing, ProcedureMetadata candidate) {
        return existing != null
                && java.util.Objects.equals(existing.qualifiedName(), candidate.qualifiedName())
                && java.util.Objects.equals(existing.rawSql(), candidate.rawSql());
    }

    public void clearParsed() {
        synchronized (lock) {
            parsed.clear();
            parsedUpdatedAt = Instant.now();
        }
        save();
        LOG.info("已清空数据库过程解析结果（目录保留，重启后仍可在存储过程页签看到过程名）");
    }

    public void clearAll() {
        synchronized (lock) {
            catalogue.clear();
            parsed.clear();
            catalogueLoadedAt = null;
            parsedUpdatedAt = null;
        }
        save();
    }

    /**
     * Catalogue entries that have no parsed result yet, shaped as regular procedures so they show up
     * in the 存储过程 tab. {@code rawSql} is empty, which is the marker used to read the source text
     * on demand when the user opens the procedure.
     */
    public List<ProcedureMetadata> placeholderProcedures() {
        List<ProcedureMetadata> placeholders = new ArrayList<>();
        synchronized (lock) {
            for (CatalogProcedureSummary row : catalogue.values()) {
                if (parsed.containsKey(key(row.qualifiedName()))) {
                    continue;
                }
                placeholders.add(placeholder(row));
            }
        }
        return placeholders;
    }

    public Stats stats() {
        synchronized (lock) {
            java.util.TreeSet<String> databases = new java.util.TreeSet<>();
            for (CatalogProcedureSummary row : catalogue.values()) {
                if (row.databaseName() != null && !row.databaseName().isBlank()) {
                    databases.add(row.databaseName().trim().toUpperCase(Locale.ROOT));
                }
            }
            return new Stats(
                    catalogue.size(),
                    parsed.size(),
                    Math.max(0, catalogue.size() - parsed.size()),
                    catalogueLoadedAt,
                    parsedUpdatedAt,
                    lastError,
                    List.copyOf(databases));
        }
    }

    public void setLastError(String message) {
        lastError = message == null ? "" : message;
    }

    static String key(String qualifiedName) {
        return qualifiedName == null ? "" : qualifiedName.trim().toLowerCase(Locale.ROOT);
    }

    /** True for procedures that came from the database rather than from a local .sql file. */
    public static boolean isDatabaseProcedure(ProcedureMetadata procedure) {
        return procedure != null
                && procedure.sourceFile() != null
                && procedure.sourceFile().startsWith(SOURCE_PREFIX);
    }

    static String normalizeName(String value) {
        return value == null ? "" : value.replace("\"", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /**
     * "尚未解析" row: the catalogue knows the procedure but its source text has not been read yet, so
     * the counts are zero and {@code rawSql} is empty (that is the marker for reading it on demand).
     */
    public static ProcedureMetadata placeholder(CatalogProcedureSummary row) {
        String database = normalizeName(row.databaseName());
        String name = normalizeName(row.procedureName());
        if (database.isBlank()) {
            database = "PUBLIC";
        }
        String qualifiedName = database + "." + name;
        String owner = blankTo(row.ownerName(), "未知")
                + (blankTo(row.ownerType(), "").isEmpty() ? "" : " / " + row.ownerType());
        Map<String, String> documentation = new LinkedHashMap<>();
        documentation.put("存储过程名称", qualifiedName);
        documentation.put("存储过程功能", "尚未解析：点开该过程会读取源码并分析（首次约 4~5 秒，之后走缓存）");
        documentation.put("创建时间", blankTo(row.createTime(), "未知"));
        documentation.put("拥有者", owner);
        return new ProcedureMetadata(
                SOURCE_PREFIX + qualifiedName,
                database,
                name,
                qualifiedName,
                SOURCE_PREFIX + qualifiedName,
                1,
                1,
                "",
                documentation,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "");
    }

    private void evictOldestParsed() {
        int max = Math.max(1, properties.getIndexMaxEntries());
        while (parsed.size() > max) {
            String oldest = parsed.keySet().iterator().next();
            parsed.remove(oldest);
        }
    }

    private void save() {
        Path file = indexFile();
        PersistedState state;
        synchronized (lock) {
            state = new PersistedState(
                    catalogueLoadedAt == null ? null : catalogueLoadedAt.toString(),
                    parsedUpdatedAt == null ? null : parsedUpdatedAt.toString(),
                    new ArrayList<>(catalogue.values()),
                    new ArrayList<>(parsed.values()));
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(state), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            lastError = "写入索引文件失败：" + exception.getMessage();
            LOG.warn("写入数据库过程索引失败 {}：{}", file, exception.getMessage());
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Persisted shape; timestamps stay ISO strings so the file needs no extra Jackson module. */
    private record PersistedState(
            String catalogueLoadedAt,
            String parsedUpdatedAt,
            List<CatalogProcedureSummary> catalogue,
            List<ProcedureMetadata> procedures
    ) {
    }

    public record Stats(
            int catalogueSize,
            int parsedSize,
            int pendingSize,
            Instant catalogueLoadedAt,
            Instant parsedUpdatedAt,
            String lastError,
            List<String> databases
    ) {
    }
}
