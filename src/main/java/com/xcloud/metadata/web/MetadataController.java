package com.xcloud.metadata.web;

import com.xcloud.metadata.model.ColumnMetadata;
import com.xcloud.metadata.model.ColumnLineage;
import com.xcloud.metadata.model.ColumnLineageTreeNode;
import com.xcloud.metadata.model.MetadataSnapshot;
import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.model.ProcedureParameter;
import com.xcloud.metadata.model.SourceFileSummary;
import com.xcloud.metadata.model.TableMetadata;
import com.xcloud.metadata.service.MetadataService;
import com.xcloud.metadata.service.AnnotationStore;
import com.xcloud.metadata.security.AuthUser;
import com.xcloud.metadata.security.AuthenticationSession;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class MetadataController {
    private final MetadataService metadataService;
    private final AnnotationStore annotationStore;

    public MetadataController(MetadataService metadataService, AnnotationStore annotationStore) {
        this.metadataService = metadataService;
        this.annotationStore = annotationStore;
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        MetadataSnapshot snapshot = metadataService.snapshot();
        int tableCommentCount = (int) snapshot.tables().stream().filter(TableMetadata::hasComment).count();
        int columnCommentCount = snapshot.tables().stream().mapToInt(TableMetadata::commentedColumnCount).sum();
        int partitionedTableCount = (int) snapshot.tables().stream().filter(table -> present(table.partitionedBy())).count();
        int orphanTableCount = (int) snapshot.tables().stream().filter(table -> isOrphan(snapshot, table)).count();
        return new SummaryResponse(
                snapshot.loadedAt(),
                snapshot.tables().size(),
                snapshot.columnCount(),
                snapshot.procedures().size(),
                tableCommentCount,
                columnCommentCount,
                partitionedTableCount,
                orphanTableCount,
                grouped(snapshot.tables().stream().map(TableMetadata::schema).toList()),
                grouped(snapshot.procedures().stream().map(ProcedureMetadata::schema).toList()),
                snapshot.sourceFiles()
        );
    }

    @PostMapping("/reload")
    public SummaryResponse reload() throws IOException {
        metadataService.reload();
        return summary();
    }

    @GetMapping("/schemas")
    public List<String> schemas() {
        MetadataSnapshot snapshot = metadataService.snapshot();
        return snapshot.tables().stream()
                .map(TableMetadata::schema)
                .distinct()
                .sorted()
                .toList();
    }

    @GetMapping("/tables")
    public List<TableListItem> tables(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "schema", defaultValue = "") String schema,
            @RequestParam(name = "relation", defaultValue = "") String relation,
            @RequestParam(name = "tableType", defaultValue = "") String tableType,
            @RequestParam(name = "tableStatus", defaultValue = "") String tableStatus
    ) {
        String needle = normalize(q);
        String schemaNeedle = normalize(schema);
        MetadataSnapshot snapshot = metadataService.snapshot();
        Map<String, AnnotationStore.TableProfile> profiles = annotationStore.tableProfiles();
        return snapshot.tables().stream()
                .filter(table -> schemaNeedle.isBlank() || normalize(table.schema()).equals(schemaNeedle))
                .filter(table -> relationMatches(relation, isOrphan(snapshot, table)))
                .filter(table -> profileMatches(profiles.getOrDefault(MetadataService.normalize(table.qualifiedName()), AnnotationStore.TableProfile.empty()), tableType, tableStatus))
                .filter(table -> needle.isBlank() || tableMatches(table, needle))
                .map(table -> tableListItem(table, snapshot, profiles))
                .toList();
    }

    @GetMapping("/tables/detail")
    public TableDetailResponse tableDetail(
            @RequestParam(name = "id", required = false) String id,
            @RequestParam(name = "name", required = false) String name
    ) {
        String lookup = present(id) ? id : name;
        if (!present(lookup)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id or name is required");
        }
        MetadataSnapshot snapshot = metadataService.snapshot();
        TableMetadata table = metadataService.findTable(lookup)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found: " + lookup));
        List<ProcedureListItem> referencedBy = snapshot.proceduresByReferencedTable()
                .getOrDefault(MetadataService.normalize(table.qualifiedName()), List.of())
                .stream()
                .map(procedure -> procedureListItem(procedure))
                .toList();
        List<ProcedureListItem> targetProcedures = snapshot.proceduresByTargetTable()
                .getOrDefault(MetadataService.normalize(table.qualifiedName()), List.of())
                .stream()
                .map(MetadataController::procedureListItem)
                .toList();
        List<ProcedureListItem> sourceProcedures = snapshot.proceduresBySourceTable()
                .getOrDefault(MetadataService.normalize(table.qualifiedName()), List.of())
                .stream()
                .map(MetadataController::procedureListItem)
                .toList();
        Map<String, List<ColumnLineage>> columnLineage = snapshot.columnLineageByTargetTable()
                .getOrDefault(MetadataService.normalize(table.qualifiedName()), List.of())
                .stream()
                .collect(Collectors.groupingBy(
                        lineage -> normalize(lineage.targetColumn()),
                        java.util.LinkedHashMap::new,
                        Collectors.toList()
                ));
        return new TableDetailResponse(
                table,
                targetProcedures,
                sourceProcedures,
                referencedBy,
                isOrphan(snapshot, table),
                annotationStore.tableProfile(table.schema(), table.name()),
                annotationStore.dictionaries(table.schema(), table.name()),
                columnLineage
        );
    }

    @GetMapping("/columns/lineage")
    public ColumnLineageTreeNode columnLineage(
            @RequestParam(name = "table") String table,
            @RequestParam(name = "column") String column,
            @RequestParam(name = "maxDepth", defaultValue = "20") int maxDepth
    ) {
        if (!present(table) || !present(column)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "table and column are required");
        }
        metadataService.findTable(table)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found: " + table));
        return metadataService.columnLineageTree(table, column, maxDepth);
    }

    @PostMapping("/tables/profile")
    public AnnotationStore.TableProfile saveTableProfile(@RequestBody TableProfileRequest request) {
        TableMetadata table = requireTable(request.schema(), request.table());
        annotationStore.saveTableProfile(table.schema(), table.name(), new AnnotationStore.TableProfile(
                request.comment(), request.chineseName(), request.dateColumn(), request.loadMode(), request.tableType(), request.tableStatus()));
        return annotationStore.tableProfile(table.schema(), table.name());
    }

    @PostMapping("/tables/annotation")
    public TableAnnotationResponse saveTableAnnotation(@RequestBody TableAnnotationRequest request) {
        TableMetadata table = requireTable(request.schema(), request.table());
        annotationStore.saveTableComment(table.schema(), table.name(), request.comment());
        return new TableAnnotationResponse(table.schema(), table.name(), annotationStore.tableComment(table.schema(), table.name()));
    }

    @PostMapping("/tables/code-values")
    public ColumnCodeValuesResponse saveColumnCodeValues(@RequestBody ColumnCodeValuesRequest request) {
        TableMetadata table = requireTable(request.schema(), request.table());
        boolean columnExists = table.columns().stream()
                .anyMatch(column -> normalize(column.name()).equals(normalize(request.column())));
        if (!columnExists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Column not found: " + request.column());
        }
        List<AnnotationStore.CodeValue> values = request.values() == null ? List.of() : request.values().stream()
                .map(value -> new AnnotationStore.CodeValue(value.value(), value.label()))
                .toList();
        annotationStore.configureManualDictionary(table.schema(), table.name(), request.column());
        annotationStore.replaceCodeValues(table.schema(), table.name(), request.column(), values);
        return new ColumnCodeValuesResponse(
                table.schema(), table.name(), request.column(), annotationStore.dictionaries(table.schema(), table.name())
                        .getOrDefault(normalize(request.column()), new AnnotationStore.ColumnDictionary("MANUAL", "", List.of()))
        );
    }

    @PostMapping("/tables/code-values/reference")
    public ColumnCodeValuesResponse referenceGeneralCodeSet(@RequestBody GeneralReferenceRequest request) {
        TableMetadata table = requireTable(request.schema(), request.table());
        boolean columnExists = table.columns().stream().anyMatch(column -> normalize(column.name()).equals(normalize(request.column())));
        if (!columnExists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Column not found: " + request.column());
        annotationStore.configureGeneralDictionary(table.schema(), table.name(), request.column(), request.codeSet());
        return new ColumnCodeValuesResponse(table.schema(), table.name(), request.column(), annotationStore.dictionaries(table.schema(), table.name())
                .getOrDefault(normalize(request.column()), new AnnotationStore.ColumnDictionary("MANUAL", "", List.of())));
    }

    @GetMapping("/general-code-sets")
    public List<AnnotationStore.GeneralCodeSet> generalCodeSets() {
        return annotationStore.generalCodeSets();
    }

    @PostMapping("/general-code-sets")
    public List<AnnotationStore.GeneralCodeSet> saveGeneralCodeSet(@RequestBody GeneralCodeSetRequest request) {
        List<AnnotationStore.CodeValue> values = request.values() == null ? List.of() : request.values().stream()
                .map(value -> new AnnotationStore.CodeValue(value.value(), value.label())).toList();
        annotationStore.saveGeneralCodeSet(request.codeSet(), request.name(), values);
        return annotationStore.generalCodeSets();
    }

    @GetMapping("/annotations/overview")
    public AnnotationsOverview annotationsOverview(
            @RequestParam(name = "themeId", required = false) Long themeId
    ) {
        long activeThemeId = requireCustomGroupTheme(themeId);
        return new AnnotationsOverview(
                annotationStore.customGroupThemes(),
                activeThemeId,
                annotationStore.customGroups(activeThemeId),
                annotationStore.customGroupAssignments(activeThemeId),
                annotationStore.adjustmentMarks()
        );
    }

    @PostMapping("/custom-group-themes")
    public AnnotationStore.CustomGroupTheme saveCustomGroupTheme(
            @RequestBody CustomGroupThemeRequest request,
            HttpServletRequest httpRequest
    ) {
        if (!present(request.themeName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "themeName is required");
        }
        return annotationStore.createCustomGroupTheme(
                request.themeName(), currentUser(httpRequest).username()
        );
    }

    @DeleteMapping("/custom-group-themes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCustomGroupTheme(@PathVariable("id") long id) {
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid theme id");
        }
        annotationStore.deleteCustomGroupTheme(id);
    }

    @PostMapping("/custom-groups")
    public AnnotationStore.CustomGroup saveCustomGroup(
            @RequestBody CustomGroupRequest request,
            HttpServletRequest httpRequest
    ) {
        requireObjectType(request.objectType());
        if (!present(request.groupName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupName is required");
        }
        long themeId = requireCustomGroupTheme(request.themeId());
        return annotationStore.createCustomGroup(
                themeId, request.objectType(),
                request.groupName(),
                request.parentId(),
                currentUser(httpRequest).username()
        );
    }

    @DeleteMapping("/custom-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCustomGroup(
            @PathVariable("id") long id,
            @RequestParam(name = "themeId", required = false) Long themeId
    ) {
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid group id");
        }
        annotationStore.deleteCustomGroup(requireCustomGroupTheme(themeId), id);
    }

    @PostMapping("/custom-groups/assignment")
    public AnnotationsOverview assignCustomGroup(
            @RequestBody CustomGroupAssignmentRequest request,
            HttpServletRequest httpRequest
    ) {
        requireObject(request.objectType(), request.objectKey());
        long groupId = request.groupId() == null ? 0 : request.groupId();
        long themeId = requireCustomGroupTheme(request.themeId());
        annotationStore.assignCustomGroup(themeId, request.objectType(), request.objectKey(), groupId, currentUser(httpRequest).username());
        return annotationsOverview(themeId);
    }

    @PostMapping("/adjustments")
    public AnnotationsOverview saveAdjustment(
            @RequestBody AdjustmentRequest request,
            HttpServletRequest httpRequest
    ) {
        requireObject(request.objectType(), request.objectKey());
        annotationStore.saveAdjustmentMark(
                request.objectType(),
                request.objectKey(),
                request.objectName(),
                request.note(),
                currentUser(httpRequest).username(),
                request.marked()
        );
        return annotationsOverview(request.themeId());
    }

    @GetMapping("/procedures")
    public List<ProcedureListItem> procedures(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "schema", defaultValue = "") String schema
    ) {
        String needle = normalize(q);
        String schemaNeedle = normalize(schema);
        return metadataService.snapshot().procedures().stream()
                .filter(procedure -> schemaNeedle.isBlank() || normalize(procedure.schema()).equals(schemaNeedle))
                .filter(procedure -> needle.isBlank() || procedureMatches(procedure, needle))
                .map(MetadataController::procedureListItem)
                .toList();
    }

    @GetMapping("/procedures/detail")
    public ProcedureMetadata procedureDetail(
            @RequestParam(name = "id", required = false) String id,
            @RequestParam(name = "name", required = false) String name
    ) {
        String lookup = present(id) ? id : name;
        if (!present(lookup)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id or name is required");
        }
        return metadataService.findProcedure(lookup)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Procedure not found: " + lookup));
    }

    private static TableListItem tableListItem(TableMetadata table, MetadataSnapshot snapshot, Map<String, AnnotationStore.TableProfile> profiles) {
        AnnotationStore.TableProfile profile = profiles.getOrDefault(MetadataService.normalize(table.qualifiedName()), AnnotationStore.TableProfile.empty());
        int referencedByCount = referencedByCount(snapshot, table);
        int targetProcedureCount = proceduresForTable(snapshot.proceduresByTargetTable(), table).size();
        int sourceProcedureCount = proceduresForTable(snapshot.proceduresBySourceTable(), table).size();
        return new TableListItem(
                table.id(),
                table.schema(),
                table.name(),
                table.qualifiedName(),
                table.comment(),
                profile.comment(),
                profile.chineseName(),
                profile.tableType(),
                profile.tableStatus(),
                table.sourceFile(),
                table.startLine(),
                table.endLine(),
                table.engine(),
                table.partitionedBy(),
                table.columnCount(),
                table.commentedColumnCount(),
                referencedByCount,
                targetProcedureCount,
                sourceProcedureCount,
                referencedByCount == 0
        );
    }

    private static ProcedureListItem procedureListItem(ProcedureMetadata procedure) {
        return new ProcedureListItem(
                procedure.id(),
                procedure.schema(),
                procedure.name(),
                procedure.qualifiedName(),
                procedure.sourceFile(),
                procedure.startLine(),
                procedure.endLine(),
                procedure.parameterCount(),
                procedure.targetTables().size(),
                procedure.sourceTables().size(),
                procedure.referencedTables().size(),
                procedure.calledProcedures().size(),
                procedure.doc("存储过程名称"),
                procedure.doc("存储过程功能"),
                procedure.doc("创建时间"),
                procedure.targetTables(),
                procedure.sourceTables()
        );
    }

    private static boolean tableMatches(TableMetadata table, String needle) {
        if (contains(table.qualifiedName(), needle) || contains(table.comment(), needle)) {
            return true;
        }
        return table.columns().stream().anyMatch(column ->
                contains(column.name(), needle)
                        || contains(column.comment(), needle)
                        || contains(column.dataType(), needle)
        );
    }

    private static boolean procedureMatches(ProcedureMetadata procedure, String needle) {
        if (contains(procedure.qualifiedName(), needle) || contains(procedure.signature(), needle)) {
            return true;
        }
        if (procedure.documentation().values().stream().anyMatch(value -> contains(value, needle))) {
            return true;
        }
        if (procedure.referencedTables().stream().anyMatch(value -> contains(value, needle))) {
            return true;
        }
        if (procedure.calledProcedures().stream().anyMatch(value -> contains(value, needle))) {
            return true;
        }
        return procedure.parameters().stream().anyMatch(parameter -> parameterMatches(parameter, needle));
    }

    private static boolean parameterMatches(ProcedureParameter parameter, String needle) {
        return contains(parameter.name(), needle) || contains(parameter.dataType(), needle) || contains(parameter.comment(), needle);
    }

    private static int referencedByCount(MetadataSnapshot snapshot, TableMetadata table) {
        return snapshot.proceduresByReferencedTable()
                .getOrDefault(MetadataService.normalize(table.qualifiedName()), List.of())
                .size();
    }

    private static List<ProcedureMetadata> proceduresForTable(
            Map<String, List<ProcedureMetadata>> index,
            TableMetadata table
    ) {
        return index.getOrDefault(MetadataService.normalize(table.qualifiedName()), List.of());
    }

    private static boolean isOrphan(MetadataSnapshot snapshot, TableMetadata table) {
        String key = MetadataService.normalize(table.qualifiedName());
        return snapshot.proceduresByTargetTable().getOrDefault(key, List.of()).isEmpty()
                && snapshot.proceduresBySourceTable().getOrDefault(key, List.of()).isEmpty();
    }

    private TableMetadata requireTable(String schema, String name) {
        String lookup = MetadataService.normalize(schema) + "." + MetadataService.normalize(name);
        return metadataService.findTable(lookup)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found: " + lookup));
    }

    private void requireObject(String objectType, String objectKey) {
        String type = requireObjectType(objectType);
        if (!present(objectKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "objectKey is required");
        }
        boolean exists = "TABLE".equals(type)
                ? metadataService.findTable(objectKey).isPresent()
                : metadataService.findProcedure(objectKey).isPresent();
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Object not found: " + objectKey);
        }
    }

    private static String requireObjectType(String objectType) {
        String type = normalize(objectType);
        if (!"TABLE".equals(type) && !"PROCEDURE".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "objectType must be TABLE or PROCEDURE");
        }
        return type;
    }

    private static AuthUser currentUser(HttpServletRequest request) {
        AuthUser user = AuthenticationSession.current(request.getSession(false));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return user;
    }

    private long requireCustomGroupTheme(Long themeId) {
        long resolved = themeId == null || themeId <= 0
                ? annotationStore.defaultCustomGroupThemeId()
                : themeId;
        if (!annotationStore.customGroupThemeExists(resolved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Custom group theme not found: " + resolved);
        }
        return resolved;
    }

    private static boolean relationMatches(String relation, boolean orphan) {
        return switch (normalize(relation)) {
            case "ORPHAN" -> orphan;
            case "LINKED" -> !orphan;
            default -> true;
        };
    }

    private static boolean profileMatches(AnnotationStore.TableProfile profile, String type, String status) {
        return (normalize(type).isBlank() || normalize(profile.tableType()).equals(normalize(type)))
                && (normalize(status).isBlank() || normalize(profile.tableStatus()).equals(normalize(status)));
    }

    private static boolean contains(String value, String normalizedNeedle) {
        return value != null && normalize(value).contains(normalizedNeedle);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Long> grouped(List<String> values) {
        return values.stream()
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, java.util.LinkedHashMap::new));
    }

    public record SummaryResponse(
            Instant loadedAt,
            int tableCount,
            int columnCount,
            int procedureCount,
            int tableCommentCount,
            int columnCommentCount,
            int partitionedTableCount,
            int orphanTableCount,
            Map<String, Long> tablesBySchema,
            Map<String, Long> proceduresBySchema,
            List<SourceFileSummary> sourceFiles
    ) {
    }

    public record TableListItem(
            String id,
            String schema,
            String name,
            String qualifiedName,
            String comment,
            String manualComment,
            String chineseName,
            String tableType,
            String tableStatus,
            String sourceFile,
            int startLine,
            int endLine,
            String engine,
            String partitionedBy,
            int columnCount,
            int commentedColumnCount,
            int referencedByCount,
            int targetProcedureCount,
            int sourceProcedureCount,
            boolean orphan
    ) {
    }

    public record TableDetailResponse(
            TableMetadata table,
            List<ProcedureListItem> targetProcedures,
            List<ProcedureListItem> sourceProcedures,
            List<ProcedureListItem> referencedByProcedures,
            boolean orphan,
            AnnotationStore.TableProfile profile,
            Map<String, AnnotationStore.ColumnDictionary> codeValues,
            Map<String, List<ColumnLineage>> columnLineage
    ) {
    }

    public record TableAnnotationRequest(String schema, String table, String comment) {
    }

    public record TableAnnotationResponse(String schema, String table, String comment) {
    }

    public record TableProfileRequest(String schema, String table, String comment, String chineseName, String dateColumn,
                                      String loadMode, String tableType, String tableStatus) {
    }

    public record ColumnCodeValueRequest(String value, String label) {
    }

    public record ColumnCodeValuesRequest(String schema, String table, String column, List<ColumnCodeValueRequest> values) {
    }

    public record ColumnCodeValuesResponse(String schema, String table, String column, AnnotationStore.ColumnDictionary dictionary) {
    }

    public record GeneralReferenceRequest(String schema, String table, String column, String codeSet) {
    }

    public record GeneralCodeSetRequest(String codeSet, String name, List<ColumnCodeValueRequest> values) {
    }

    public record AnnotationsOverview(
            List<AnnotationStore.CustomGroupTheme> customGroupThemes,
            long activeCustomGroupThemeId,
            List<AnnotationStore.CustomGroup> customGroups,
            Map<String, AnnotationStore.CustomGroupAssignment> assignments,
            List<AnnotationStore.AdjustmentMark> adjustments
    ) {
    }

    public record CustomGroupThemeRequest(String themeName) {
    }

    public record CustomGroupRequest(String objectType, String groupName, Long parentId, Long themeId) {
    }

    public record CustomGroupAssignmentRequest(String objectType, String objectKey, Long groupId, Long themeId) {
    }

    public record AdjustmentRequest(String objectType, String objectKey, String objectName, String note, boolean marked, Long themeId) {
    }

    public record ProcedureListItem(
            String id,
            String schema,
            String name,
            String qualifiedName,
            String sourceFile,
            int startLine,
            int endLine,
            int parameterCount,
            int targetTableCount,
            int sourceTableCount,
            int referencedTableCount,
            int calledProcedureCount,
            String title,
            String description,
            String createdAt,
            List<String> targetTables,
            List<String> sourceTables
    ) {
    }
}
