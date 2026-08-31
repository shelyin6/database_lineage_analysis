package com.xcloud.metadata.service;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.model.ColumnLineage;
import com.xcloud.metadata.model.ColumnLineageTreeNode;
import com.xcloud.metadata.model.MetadataSnapshot;
import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.model.SourceFileSummary;
import com.xcloud.metadata.model.TableMetadata;
import com.xcloud.metadata.parser.FieldLineageParser;
import com.xcloud.metadata.parser.SqlMetadataParser;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MetadataService {
    private static final java.util.Set<String> DATA_FLOW_IGNORED_PROCEDURES = java.util.Set.of(
            "ALMP.PROC_ALMP_ADD_PARTITION"
    );

    private final MetadataProperties properties;
    private final SqlMetadataParser parser = new SqlMetadataParser();
    private final FieldLineageParser fieldLineageParser = new FieldLineageParser();
    private final SourceFileResolver sourceFileResolver;
    private volatile MetadataSnapshot snapshot;

    public MetadataService(MetadataProperties properties) {
        this.properties = properties;
        this.sourceFileResolver = new SourceFileResolver(properties);
    }

    @PostConstruct
    public void loadOnStartup() throws IOException {
        reload();
    }

    public synchronized MetadataSnapshot reload() throws IOException {
        List<TableMetadata> tables = new ArrayList<>();
        List<ProcedureMetadata> procedures = new ArrayList<>();
        List<SourceFileSummary> sourceFiles = new ArrayList<>();

        if (usesConfiguredFileLists()) {
            loadConfiguredFiles(tables, procedures, sourceFiles);
        } else {
            loadAllSqlFiles(tables, procedures, sourceFiles);
        }

        procedures = parser.linkProcedureCalls(procedures);
        tables.sort(Comparator.comparing(TableMetadata::schema).thenComparing(TableMetadata::name).thenComparing(TableMetadata::sourceFile));
        procedures.sort(Comparator.comparing(ProcedureMetadata::schema).thenComparing(ProcedureMetadata::name).thenComparing(ProcedureMetadata::sourceFile));

        List<ProcedureMetadata> dataFlowProcedures = procedures.stream()
                .filter(procedure -> !isDataFlowIgnored(procedure))
                .toList();
        Map<String, List<ProcedureMetadata>> proceduresByTable = buildProcedureTableIndex(dataFlowProcedures, ProcedureMetadata::referencedTables);
        Map<String, List<ProcedureMetadata>> proceduresByTargetTable = buildProcedureTableIndex(dataFlowProcedures, ProcedureMetadata::targetTables);
        Map<String, List<ProcedureMetadata>> proceduresBySourceTable = buildProcedureTableIndex(dataFlowProcedures, ProcedureMetadata::sourceTables);
        Map<String, TableMetadata> tablesByQualifiedName = tables.stream()
                .collect(Collectors.toMap(
                        table -> normalize(table.qualifiedName()),
                        table -> table,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, List<ColumnLineage>> columnLineageByTargetTable = buildColumnLineageIndex(
                dataFlowProcedures,
                tablesByQualifiedName
        );
        snapshot = new MetadataSnapshot(
                Instant.now(),
                List.copyOf(tables),
                List.copyOf(procedures),
                List.copyOf(sourceFiles),
                proceduresByTable,
                proceduresByTargetTable,
                proceduresBySourceTable,
                columnLineageByTargetTable
        );
        return snapshot;
    }

    public MetadataSnapshot snapshot() {
        MetadataSnapshot current = snapshot;
        if (current == null) {
            throw new IllegalStateException("Metadata has not been loaded");
        }
        return current;
    }

    public Optional<TableMetadata> findTable(String idOrName) {
        String key = normalize(idOrName);
        return snapshot().tables().stream()
                .filter(table -> normalize(table.id()).equals(key)
                        || normalize(table.qualifiedName()).equals(key)
                        || normalize(table.name()).equals(key))
                .findFirst();
    }

    public Optional<ProcedureMetadata> findProcedure(String idOrName) {
        String key = normalize(idOrName);
        return snapshot().procedures().stream()
                .filter(procedure -> normalize(procedure.id()).equals(key)
                        || normalize(procedure.qualifiedName()).equals(key)
                        || normalize(procedure.name()).equals(key))
                .findFirst();
    }

    public ColumnLineageTreeNode columnLineageTree(String table, String column, int requestedMaxDepth) {
        int maxDepth = Math.max(1, Math.min(requestedMaxDepth, 50));
        String normalizedTable = normalize(table);
        String normalizedColumn = normalize(column);
        return buildColumnLineageNode(
                normalizedTable,
                normalizedColumn,
                "ROOT",
                "",
                "",
                0,
                0,
                maxDepth,
                Set.of()
        );
    }

    public static boolean isDataFlowIgnored(ProcedureMetadata procedure) {
        return DATA_FLOW_IGNORED_PROCEDURES.contains(normalize(procedure.qualifiedName()));
    }

    private boolean usesConfiguredFileLists() {
        return !properties.getTableFiles().isEmpty() || !properties.getProcedureFiles().isEmpty();
    }

    private void loadConfiguredFiles(
            List<TableMetadata> tables,
            List<ProcedureMetadata> procedures,
            List<SourceFileSummary> sourceFiles
    ) throws IOException {
        for (String file : properties.getTableFiles()) {
            SourceFile sourceFile = sourceFileResolver.sourceFile(file, resolveConfiguredPath(file));
            String sql = readSql(sourceFile.path());
            List<TableMetadata> parsed = parser.parseTables(sourceFile.metadataPrefix(), sql);
            tables.addAll(parsed);
            sourceFiles.add(summary(sourceFile, "table", sql, parsed.size()));
        }

        for (String file : properties.getProcedureFiles()) {
            SourceFile sourceFile = sourceFileResolver.sourceFile(file, resolveConfiguredPath(file));
            String sql = readSql(sourceFile.path());
            List<ProcedureMetadata> parsed = parser.parseProcedures(sourceFile.metadataPrefix(), sql);
            procedures.addAll(parsed);
            sourceFiles.add(summary(sourceFile, "procedure", sql, parsed.size()));
        }
    }

    private void loadAllSqlFiles(
            List<TableMetadata> tables,
            List<ProcedureMetadata> procedures,
            List<SourceFileSummary> sourceFiles
    ) throws IOException {
        Path directory = properties.effectiveSqlDirectory();
        if (!Files.isDirectory(directory)) {
            throw new IOException("SQL directory does not exist: " + directory);
        }

        List<Path> files = sourceFileResolver.listSqlFiles(directory);
        if (files.isEmpty()) {
            throw new IOException("No .sql files found in: " + directory);
        }

        for (Path path : files) {
            SourceFile sourceFile = sourceFileResolver.sourceFile(path);
            String file = sourceFile.metadataPrefix();
            String sql = Files.readString(sourceFile.path(), StandardCharsets.UTF_8);
            List<ProcedureMetadata> detectedProcedures = parser.parseProcedures(file, sql);
            List<TableMetadata> parsedTables;
            List<ProcedureMetadata> parsedProcedures;
            if (looksLikeProcedureFile(file) || !detectedProcedures.isEmpty()) {
                parsedTables = List.of();
                parsedProcedures = detectedProcedures;
            } else {
                parsedTables = parser.parseTables(file, sql);
                parsedProcedures = List.of();
            }
            tables.addAll(parsedTables);
            procedures.addAll(parsedProcedures);
            sourceFiles.add(summary(sourceFile, sourceKind(parsedTables.size(), parsedProcedures.size()), sql,
                    parsedTables.size() + parsedProcedures.size()));
        }
    }

    private Path resolveConfiguredPath(String file) {
        Path path = Path.of(file);
        if (path.isAbsolute() || file.length() > 0 && file.charAt(0) == '.') {
            return path;
        }
        return properties.effectiveSqlDirectory().resolve(path);
    }

    private String readSql(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String sourceKind(int tableCount, int procedureCount) {
        if (tableCount > 0 && procedureCount > 0) {
            return "mixed";
        }
        if (tableCount > 0) {
            return "table";
        }
        if (procedureCount > 0) {
            return "procedure";
        }
        return "sql";
    }

    private boolean looksLikeProcedureFile(String file) {
        String normalized = file.toLowerCase(Locale.ROOT);
        return normalized.contains("proc") || normalized.contains("procedure");
    }

    private SourceFileSummary summary(SourceFile sourceFile, String kind, String sql, int objectCount) {
        return new SourceFileSummary(
                sourceFile.display(),
                kind,
                sql.getBytes(StandardCharsets.UTF_8).length,
                sql.split("\\R", -1).length,
                objectCount
        );
    }

    private Map<String, List<ProcedureMetadata>> buildProcedureTableIndex(
            List<ProcedureMetadata> procedures,
            java.util.function.Function<ProcedureMetadata, List<String>> tableSelector
    ) {
        Map<String, List<ProcedureMetadata>> index = new LinkedHashMap<>();
        for (ProcedureMetadata procedure : procedures) {
            for (String table : tableSelector.apply(procedure)) {
                index.computeIfAbsent(normalize(table), ignored -> new ArrayList<>()).add(procedure);
            }
        }
        Map<String, List<ProcedureMetadata>> ordered = index.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .sorted(Comparator.comparing(ProcedureMetadata::schema).thenComparing(ProcedureMetadata::name))
                                .toList(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return Collections.unmodifiableMap(ordered);
    }

    private Map<String, List<ColumnLineage>> buildColumnLineageIndex(
            List<ProcedureMetadata> procedures,
            Map<String, TableMetadata> tablesByQualifiedName
    ) {
        Map<String, List<ColumnLineage>> index = new LinkedHashMap<>();
        for (ProcedureMetadata procedure : procedures) {
            for (ColumnLineage lineage : fieldLineageParser.parse(procedure, tablesByQualifiedName)) {
                index.computeIfAbsent(normalize(lineage.targetTable()), ignored -> new ArrayList<>()).add(lineage);
            }
        }
        Map<String, List<ColumnLineage>> ordered = index.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        ordered.replaceAll((table, lineages) -> lineages.stream()
                .sorted(Comparator.comparing(ColumnLineage::targetColumn)
                        .thenComparing(ColumnLineage::qualifiedProcedureName)
                        .thenComparingInt(ColumnLineage::line))
                .toList());
        return Collections.unmodifiableMap(ordered);
    }

    private ColumnLineageTreeNode buildColumnLineageNode(
            String table,
            String column,
            String status,
            String procedureName,
            String sourceFile,
            int line,
            int depth,
            int maxDepth,
            Set<String> path
    ) {
        String nodeKey = normalize(table) + "|" + normalize(column);
        if (path.contains(nodeKey)) {
            return new ColumnLineageTreeNode(
                    table,
                    column,
                    "CYCLE",
                    procedureName,
                    sourceFile,
                    line,
                    List.of()
            );
        }
        if (depth >= maxDepth) {
            return new ColumnLineageTreeNode(
                    table,
                    column,
                    "DEPTH_LIMIT",
                    procedureName,
                    sourceFile,
                    line,
                    List.of()
            );
        }

        List<ColumnLineage> relations = snapshot().columnLineageByTargetTable()
                .getOrDefault(normalize(table), List.of())
                .stream()
                .filter(item -> normalize(item.targetColumn()).equals(normalize(column)))
                .toList();
        if (relations.isEmpty()) {
            return new ColumnLineageTreeNode(
                    table,
                    column,
                    status.equals("ROOT") ? "NO_SOURCE" : "ORIGINAL",
                    procedureName,
                    sourceFile,
                    line,
                    List.of()
            );
        }

        Set<String> nextPath = new java.util.LinkedHashSet<>(path);
        nextPath.add(nodeKey);
        List<ColumnLineageTreeNode> children = relations.stream()
                .map(relation -> buildColumnLineageNode(
                        relation.sourceTable(),
                        relation.sourceColumn(),
                        "DIRECT",
                        relation.qualifiedProcedureName(),
                        relation.sourceFile(),
                        relation.line(),
                        depth + 1,
                        maxDepth,
                        nextPath
                ))
                .toList();
        return new ColumnLineageTreeNode(
                table,
                column,
                status,
                procedureName,
                sourceFile,
                line,
                children
        );
    }

    public static String normalize(String value) {
        return value == null ? "" : value.replace("\"", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
