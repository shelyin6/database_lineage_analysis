package com.xcloud.metadata.parser;

import com.xcloud.metadata.model.ColumnMetadata;
import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.model.ProcedureParameter;
import com.xcloud.metadata.model.TableMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlMetadataParser {
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "\\bCREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+((?:\"[^\"]+\"|[A-Z0-9_$]+)(?:\\s*\\.\\s*(?:\"[^\"]+\"|[A-Z0-9_$]+))?)\\s*(?:COMMENT\\s+'((?:''|[^'])*)')?\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROCEDURE_PATTERN = Pattern.compile(
            "^[ \\t]*CREATE\\s+OR\\s+REPLACE\\s+PROCEDURE\\s+((?:\"[^\"]+\"|[A-Z0-9_$]+)(?:\\s*\\.\\s*(?:\"[^\"]+\"|[A-Z0-9_$]+))?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("\\bCOMMENT\\s+'((?:''|[^'])*)'", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENGINE_PATTERN = Pattern.compile("\\bENGINE\\s*=\\s*'([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTITION_PATTERN = Pattern.compile("\\bPARTITIONED\\s+BY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NOT_NULL_PATTERN = Pattern.compile("\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_PATTERN = Pattern.compile("\\bDEFAULT\\b\\s+(.+?)(?=\\bCOMMENT\\b|\\bNOT\\s+NULL\\b|\\bNULL\\b|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TYPE_PATTERN = Pattern.compile("^([A-Z][A-Z0-9_]*(?:\\s+[A-Z][A-Z0-9_]*)?)(?:\\s*\\(([^)]*)\\))?.*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEADER_END_PATTERN = Pattern.compile("^\\s*(IS|AS)\\b", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TARGET_TABLE_PATTERN = Pattern.compile(
            "\\b(?:INSERT\\s+(?:OVERWRITE\\s+)?(?:INTO\\s+)?(?:TABLE\\s+)?|MERGE\\s+INTO\\s+|UPDATE\\s+|TRUNCATE\\s+(?:TABLE\\s+)?|DELETE\\s+FROM\\s+|ALTER\\s+TABLE\\s+)((?:[A-Z][A-Z0-9_$]*\\s*\\.\\s*)?[A-Z][A-Z0-9_$]*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_TABLE_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN|USING)\\s+((?:[A-Z][A-Z0-9_$]*\\s*\\.\\s*)?[A-Z][A-Z0-9_$]*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WITH_PATTERN = Pattern.compile("\\bWITH\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL_PATTERN = Pattern.compile("\\b((?:[A-Z][A-Z0-9_$]*\\s*\\.\\s*)?[A-Z][A-Z0-9_$]*)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_COMMENT_PATTERN = Pattern.compile("--\\s*(.*)$", Pattern.MULTILINE);
    private static final Pattern SQL_LITERAL_PATTERN = Pattern.compile("'((?:''|[^'])*)'");

    private static final Set<String> NON_COLUMN_PREFIXES = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "INDEX", "DISTRIBUTED", "PARTITIONED", "ENGINE"
    );

    private static final Set<String> BUILT_IN_CALLS = Set.of(
            "ABS", "ADD_MONTHS", "CASE", "CAST", "CEIL", "COALESCE", "COUNT", "DATE", "DECODE", "FLOOR",
            "GREATEST", "LAST_DAY", "LEAST", "LENGTH", "MAX", "MIN", "MONTHS_BETWEEN", "NVL", "NVL2",
            "REPLACE", "ROUND", "ROW_NUMBER", "SUBSTR", "SUM", "SYSDATE", "TO_CHAR", "TO_DATE", "TO_NUMBER",
            "TRIM", "UPPER", "LOWER"
    );

    public List<TableMetadata> parseTables(String sourceFile, String sql) {
        int[] lineStarts = lineStarts(sql);
        List<TableMetadata> tables = new ArrayList<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            int openParen = matcher.end() - 1;
            int closeParen = findMatchingParen(sql, openParen);
            if (closeParen < 0) {
                continue;
            }

            int endOffset = findStatementEnd(sql, closeParen);
            String rawStatement = sql.substring(matcher.start(), Math.min(sql.length(), endOffset + 1)).trim();
            String body = sql.substring(openParen + 1, closeParen);
            String tail = sql.substring(closeParen + 1, Math.min(sql.length(), endOffset + 1));
            NameParts name = splitName(matcher.group(1), inferSchema(sourceFile));
            String qualifiedName = qualify(name.schema(), name.name());
            String tableComment = unescapeSqlString(matcher.group(2));
            String engine = firstMatch(ENGINE_PATTERN, tail);
            String partitionedBy = cleanupWhitespace(firstMatch(PARTITION_PATTERN, tail));
            int startLine = lineOf(lineStarts, matcher.start());
            int endLine = lineOf(lineStarts, endOffset);
            List<ColumnMetadata> columns = parseColumns(body, openParen + 1, lineStarts);

            tables.add(new TableMetadata(
                    objectId(sourceFile, startLine, qualifiedName),
                    name.schema(),
                    name.name(),
                    qualifiedName,
                    tableComment,
                    sourceFile,
                    startLine,
                    endLine,
                    emptyToNull(engine),
                    emptyToNull(partitionedBy),
                    columns,
                    rawStatement
            ));
        }
        return tables;
    }

    public List<ProcedureMetadata> parseProcedures(String sourceFile, String sql) {
        int[] lineStarts = lineStarts(sql);
        List<ProcedureStart> starts = new ArrayList<>();
        Matcher matcher = PROCEDURE_PATTERN.matcher(sql);
        while (matcher.find()) {
            starts.add(new ProcedureStart(matcher.start(), matcher.end(), matcher.group(1)));
        }

        List<ProcedureMetadata> procedures = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            ProcedureStart start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1).start() : sql.length();
            String block = sql.substring(start.start(), end).trim();
            if (block.isBlank()) {
                continue;
            }

            NameParts name = splitName(start.rawName(), inferSchema(sourceFile));
            String qualifiedName = qualify(name.schema(), name.name());
            int startLine = lineOf(lineStarts, start.start());
            int endLine = lineOf(lineStarts, Math.max(start.start(), end - 1));
            int headerEnd = findHeaderEnd(block);
            String signature = block.substring(0, headerEnd >= 0 ? headerEnd : Math.min(block.length(), 1200)).trim();
            List<ProcedureParameter> parameters = parseParameters(block, start.end() - start.start());
            Map<String, String> documentation = parseDocumentation(block);
            String searchableSql = stripCommentsAndStringLiterals(block);
            List<String> targetTables = mergeSorted(
                    extractTables(TARGET_TABLE_PATTERN, searchableSql, name.schema()),
                    extractDynamicTables(block, TARGET_TABLE_PATTERN, name.schema())
            );
            List<String> sourceTables = mergeSorted(
                    extractSourceTables(searchableSql, name.schema()),
                    extractDynamicTables(block, SOURCE_TABLE_PATTERN, name.schema())
            );
            List<String> referencedTables = mergeSorted(targetTables, sourceTables, extractDocTables(documentation, name.schema()));

            procedures.add(new ProcedureMetadata(
                    objectId(sourceFile, startLine, qualifiedName),
                    name.schema(),
                    name.name(),
                    qualifiedName,
                    sourceFile,
                    startLine,
                    endLine,
                    signature,
                    documentation,
                    parameters,
                    targetTables,
                    sourceTables,
                    referencedTables,
                    List.of(),
                    block
            ));
        }
        return procedures;
    }

    public List<ProcedureMetadata> linkProcedureCalls(List<ProcedureMetadata> procedures) {
        Map<String, String> known = new LinkedHashMap<>();
        for (ProcedureMetadata procedure : procedures) {
            known.put(normalizeIdentifier(procedure.name()), procedure.qualifiedName());
            known.put(normalizeIdentifier(procedure.qualifiedName()), procedure.qualifiedName());
        }

        List<ProcedureMetadata> linked = new ArrayList<>(procedures.size());
        for (ProcedureMetadata procedure : procedures) {
            String searchableSql = stripCommentsAndStringLiterals(procedure.rawSql());
            Matcher matcher = CALL_PATTERN.matcher(searchableSql);
            Set<String> called = new LinkedHashSet<>();
            while (matcher.find()) {
                String token = normalizeIdentifier(matcher.group(1));
                String simple = simpleName(token);
                if (BUILT_IN_CALLS.contains(simple) || simple.equals(procedure.name())) {
                    continue;
                }
                String qualified = known.getOrDefault(token, known.get(simple));
                if (qualified != null && !qualified.equals(procedure.qualifiedName())) {
                    called.add(qualified);
                }
            }
            linked.add(procedure.withCalledProcedures(called.stream().sorted().toList()));
        }
        return linked;
    }

    private List<ColumnMetadata> parseColumns(String body, int bodyOffset, int[] lineStarts) {
        List<SplitPart> definitions = splitTopLevel(body, ',');
        List<ColumnMetadata> columns = new ArrayList<>();
        int ordinal = 1;
        for (SplitPart definition : definitions) {
            String raw = definition.text().trim();
            if (raw.isBlank()) {
                continue;
            }
            String firstToken = firstToken(raw);
            if (firstToken.isBlank() || NON_COLUMN_PREFIXES.contains(firstToken.toUpperCase(Locale.ROOT))) {
                continue;
            }

            String columnName = cleanIdentifier(firstToken);
            String working = raw.substring(firstToken.length()).trim();
            String comment = unescapeSqlString(firstMatch(COMMENT_PATTERN, working));
            working = COMMENT_PATTERN.matcher(working).replaceAll(" ").trim();

            boolean nullable = !NOT_NULL_PATTERN.matcher(raw).find();
            String defaultValue = extractDefault(working);
            working = DEFAULT_PATTERN.matcher(working).replaceAll(" ").trim();
            working = NOT_NULL_PATTERN.matcher(working).replaceAll(" ").trim();
            working = Pattern.compile("\\bNULL\\b", Pattern.CASE_INSENSITIVE).matcher(working).replaceAll(" ").trim();
            working = cleanupWhitespace(working);

            TypeParts type = parseType(working);
            int line = lineOf(lineStarts, bodyOffset + definition.offset());
            columns.add(new ColumnMetadata(
                    ordinal++,
                    columnName,
                    emptyToNull(working),
                    type.name(),
                    type.arguments(),
                    nullable,
                    defaultValue,
                    comment,
                    line,
                    raw
            ));
        }
        return columns;
    }

    private List<ProcedureParameter> parseParameters(String block, int nameEndOffset) {
        int cursor = nameEndOffset;
        while (cursor < block.length() && Character.isWhitespace(block.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= block.length() || block.charAt(cursor) != '(') {
            return List.of();
        }
        int close = findMatchingParen(block, cursor);
        if (close < 0) {
            return List.of();
        }

        String paramsBlock = block.substring(cursor + 1, close);
        List<SplitPart> parts = splitTopLevel(paramsBlock, ',');
        List<ProcedureParameter> parameters = new ArrayList<>();
        int ordinal = 1;
        for (SplitPart part : parts) {
            String raw = part.text().trim();
            if (raw.isBlank()) {
                continue;
            }
            String comment = "";
            Matcher commentMatcher = INLINE_COMMENT_PATTERN.matcher(raw);
            if (commentMatcher.find()) {
                comment = commentMatcher.group(1).trim();
            }
            String clean = stripComments(raw).trim();
            String paramName = firstToken(clean);
            if (paramName.isBlank()) {
                continue;
            }
            String rest = clean.substring(paramName.length()).trim();
            String mode = "IN";
            Matcher modeMatcher = Pattern.compile("^(IN\\s+OUT|OUT|IN)\\b", Pattern.CASE_INSENSITIVE).matcher(rest);
            if (modeMatcher.find()) {
                mode = cleanupWhitespace(modeMatcher.group(1)).toUpperCase(Locale.ROOT);
                rest = rest.substring(modeMatcher.end()).trim();
            }
            parameters.add(new ProcedureParameter(
                    ordinal++,
                    cleanIdentifier(paramName),
                    mode,
                    cleanupWhitespace(rest),
                    comment,
                    raw
            ));
        }
        return parameters;
    }

    private Map<String, String> parseDocumentation(String block) {
        Matcher matcher = Pattern.compile("/\\*(.*?)\\*/", Pattern.DOTALL).matcher(block);
        if (!matcher.find()) {
            return Map.of();
        }

        Map<String, String> docs = new LinkedHashMap<>();
        String[] lines = matcher.group(1).split("\\R");
        String lastKey = null;
        for (String line : lines) {
            String cleaned = line.replace("---------------------------------------------------", "")
                    .replace("======================================", "")
                    .trim();
            if (cleaned.isBlank()) {
                if (lastKey != null && !docs.getOrDefault(lastKey, "").isBlank()) {
                    lastKey = null;
                }
                continue;
            }
            int colon = firstColon(cleaned);
            if (colon >= 0) {
                String key = normalizeDocKey(cleaned.substring(0, colon));
                String value = cleaned.substring(colon + 1).trim();
                if (!key.isBlank()) {
                    docs.put(key, value);
                    lastKey = key;
                }
            } else if (lastKey != null) {
                docs.compute(lastKey, (key, old) -> cleanupWhitespace((old == null ? "" : old + " ") + cleaned));
            }
        }
        return docs;
    }

    private List<String> extractTables(Pattern pattern, String sql, String defaultSchema) {
        Matcher matcher = pattern.matcher(sql);
        Set<String> tables = new LinkedHashSet<>();
        while (matcher.find()) {
            String table = matcher.group(1);
            if (table == null || table.isBlank()) {
                continue;
            }
            String normalized = normalizeObjectName(table, defaultSchema);
            String simple = simpleName(normalized);
            if (!BUILT_IN_CALLS.contains(simple) && !simple.equals("DUAL") && !simple.equals("SELECT")) {
                tables.add(normalized);
            }
        }
        return tables.stream().sorted().toList();
    }

    private List<String> extractSourceTables(String sql, String defaultSchema) {
        Matcher matcher = SOURCE_TABLE_PATTERN.matcher(sql);
        Set<String> tables = new LinkedHashSet<>();
        Set<String> cteTables = extractCteTables(sql, defaultSchema);
        while (matcher.find()) {
            String table = matcher.group(1);
            if (table == null || table.isBlank()) {
                continue;
            }
            String normalized = normalizeObjectName(table, defaultSchema);
            String simple = simpleName(normalized);
            if (!BUILT_IN_CALLS.contains(simple)
                    && !simple.equals("DUAL")
                    && !simple.equals("SELECT")
                    && !cteTables.contains(normalized)
                    && !isDeleteFromTable(sql, matcher.start())) {
                tables.add(normalized);
            }
        }
        return tables.stream().sorted().toList();
    }

    private List<String> extractDynamicTables(String rawSql, Pattern pattern, String defaultSchema) {
        Matcher literalMatcher = SQL_LITERAL_PATTERN.matcher(stripComments(rawSql));
        Set<String> tables = new LinkedHashSet<>();
        while (literalMatcher.find()) {
            String fragment = unescapeSqlString(literalMatcher.group(1));
            if (!fragment.matches("(?is).*\\b(?:INSERT|MERGE|UPDATE|TRUNCATE|DELETE|ALTER|SELECT|FROM|JOIN|USING)\\b.*")) {
                continue;
            }
            if (pattern == SOURCE_TABLE_PATTERN) {
                tables.addAll(extractSourceTables(fragment, defaultSchema));
            } else {
                tables.addAll(extractTables(pattern, fragment, defaultSchema));
            }
        }
        return tables.stream().sorted().toList();
    }

    private Set<String> extractCteTables(String sql, String defaultSchema) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = WITH_PATTERN.matcher(sql);
        while (matcher.find()) {
            int cursor = matcher.end();
            while (cursor < sql.length()) {
                cursor = skipWhitespace(sql, cursor);
                IdentifierToken token = readObjectToken(sql, cursor);
                if (token == null) {
                    break;
                }
                cursor = skipWhitespace(sql, token.end());
                if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                    int close = findMatchingParen(sql, cursor);
                    if (close < 0) {
                        break;
                    }
                    cursor = skipWhitespace(sql, close + 1);
                }
                if (!matchesKeyword(sql, cursor, "AS")) {
                    break;
                }
                cursor = skipWhitespace(sql, cursor + 2);
                if (cursor >= sql.length() || sql.charAt(cursor) != '(') {
                    break;
                }
                tables.add(normalizeObjectName(token.value(), defaultSchema));
                int close = findMatchingParen(sql, cursor);
                if (close < 0) {
                    break;
                }
                cursor = skipWhitespace(sql, close + 1);
                if (cursor < sql.length() && sql.charAt(cursor) == ',') {
                    cursor++;
                    continue;
                }
                break;
            }
        }
        return tables;
    }

    private boolean isDeleteFromTable(String sql, int matchStart) {
        int start = Math.max(0, matchStart - 24);
        String prefix = sql.substring(start, matchStart).toUpperCase(Locale.ROOT);
        return prefix.matches(".*\\bDELETE\\s+$");
    }

    private List<String> extractDocTables(Map<String, String> docs, String defaultSchema) {
        Set<String> tables = new LinkedHashSet<>();
        for (String key : List.of("目标表", "源表")) {
            String value = docs.getOrDefault(key, "");
            String[] tokens = value.split("[,，;；、\\s]+");
            for (String token : tokens) {
                if (token.matches("(?i)[A-Z][A-Z0-9_$]*\\.[A-Z][A-Z0-9_$]*")) {
                    tables.add(normalizeObjectName(token, defaultSchema));
                }
            }
        }
        return tables.stream().sorted().toList();
    }

    @SafeVarargs
    private final List<String> mergeSorted(List<String>... lists) {
        Set<String> merged = new LinkedHashSet<>();
        for (List<String> list : lists) {
            merged.addAll(list);
        }
        return merged.stream().sorted().toList();
    }

    private int findHeaderEnd(String block) {
        Matcher matcher = HEADER_END_PATTERN.matcher(block);
        return matcher.find() ? matcher.start() : -1;
    }

    private int findStatementEnd(String sql, int from) {
        boolean singleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuote) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'') {
                singleQuote = true;
            } else if (c == ';') {
                return i;
            }
        }
        return sql.length() - 1;
    }

    private int findMatchingParen(String text, int openOffset) {
        int depth = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = openOffset; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuote) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'') {
                singleQuote = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private List<SplitPart> splitTopLevel(String text, char delimiter) {
        List<SplitPart> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuote) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'') {
                singleQuote = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == delimiter && depth == 0) {
                parts.add(new SplitPart(text.substring(start, i), start));
                start = i + 1;
            }
        }
        parts.add(new SplitPart(text.substring(start), start));
        return parts;
    }

    private int skipWhitespace(String text, int offset) {
        int cursor = offset;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private boolean matchesKeyword(String text, int offset, String keyword) {
        if (offset + keyword.length() > text.length()) {
            return false;
        }
        String candidate = text.substring(offset, offset + keyword.length());
        if (!candidate.equalsIgnoreCase(keyword)) {
            return false;
        }
        int before = offset - 1;
        int after = offset + keyword.length();
        boolean beforeBoundary = before < 0 || !isIdentifierChar(text.charAt(before));
        boolean afterBoundary = after >= text.length() || !isIdentifierChar(text.charAt(after));
        return beforeBoundary && afterBoundary;
    }

    private IdentifierToken readObjectToken(String text, int offset) {
        int cursor = offset;
        String first = readIdentifier(text, cursor);
        if (first.isBlank()) {
            return null;
        }
        cursor += first.length();
        int afterFirst = skipWhitespace(text, cursor);
        if (afterFirst < text.length() && text.charAt(afterFirst) == '.') {
            int afterDot = skipWhitespace(text, afterFirst + 1);
            String second = readIdentifier(text, afterDot);
            if (!second.isBlank()) {
                return new IdentifierToken(first + "." + second, afterDot + second.length());
            }
        }
        return new IdentifierToken(first, cursor);
    }

    private String readIdentifier(String text, int offset) {
        if (offset >= text.length()) {
            return "";
        }
        if (text.charAt(offset) == '"') {
            int end = text.indexOf('"', offset + 1);
            return end > offset ? text.substring(offset, end + 1) : "";
        }
        int cursor = offset;
        while (cursor < text.length() && isIdentifierChar(text.charAt(cursor))) {
            cursor++;
        }
        return cursor > offset ? text.substring(offset, cursor) : "";
    }

    private boolean isIdentifierChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#';
    }

    private String stripCommentsAndStringLiterals(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean singleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    result.append('\n');
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    result.append("  ");
                    i++;
                } else {
                    result.append(c == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (singleQuote) {
                if (c == '\'' && next == '\'') {
                    result.append("  ");
                    i++;
                } else if (c == '\'') {
                    singleQuote = false;
                    result.append(' ');
                } else {
                    result.append(c == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                result.append("  ");
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                result.append("  ");
                i++;
            } else if (c == '\'') {
                singleQuote = true;
                result.append(' ');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private String stripComments(String sql) {
        String withoutBlock = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(sql).replaceAll(" ");
        return Pattern.compile("--.*?(\\R|$)").matcher(withoutBlock).replaceAll(" ");
    }

    private TypeParts parseType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return new TypeParts("", "");
        }
        Matcher matcher = TYPE_PATTERN.matcher(dataType.trim());
        if (!matcher.matches()) {
            return new TypeParts(dataType.trim().toUpperCase(Locale.ROOT), "");
        }
        return new TypeParts(
                cleanupWhitespace(matcher.group(1)).toUpperCase(Locale.ROOT),
                emptyToNull(cleanupWhitespace(matcher.group(2)))
        );
    }

    private String extractDefault(String definition) {
        Matcher matcher = DEFAULT_PATTERN.matcher(definition);
        if (!matcher.find()) {
            return "";
        }
        return cleanupWhitespace(matcher.group(1));
    }

    private NameParts splitName(String rawName, String defaultSchema) {
        String cleaned = rawName == null ? "" : rawName.replace("\"", "").replaceAll("\\s+", "");
        String[] parts = cleaned.split("\\.", 2);
        if (parts.length == 2) {
            return new NameParts(parts[0].toUpperCase(Locale.ROOT), parts[1].toUpperCase(Locale.ROOT));
        }
        return new NameParts(defaultSchema.toUpperCase(Locale.ROOT), cleaned.toUpperCase(Locale.ROOT));
    }

    private String normalizeObjectName(String rawName, String defaultSchema) {
        NameParts parts = splitName(rawName, defaultSchema);
        return qualify(parts.schema(), parts.name());
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.replace("\"", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String simpleName(String value) {
        String normalized = normalizeIdentifier(value);
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private String qualify(String schema, String name) {
        return schema + "." + name;
    }

    private String inferSchema(String sourceFile) {
        String lower = sourceFile.toLowerCase(Locale.ROOT);
        if (lower.startsWith("almp")) {
            return "ALMP";
        }
        if (lower.startsWith("ids")) {
            return "IDS";
        }
        return "PUBLIC";
    }

    private int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private int lineOf(int[] starts, int offset) {
        int low = 0;
        int high = starts.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] <= offset) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(1, high + 1);
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String firstToken(String raw) {
        String text = raw.stripLeading();
        if (text.isBlank()) {
            return "";
        }
        if (text.charAt(0) == '"') {
            int end = text.indexOf('"', 1);
            return end > 0 ? text.substring(0, end + 1) : text;
        }
        Matcher matcher = Pattern.compile("^[^\\s,()]+").matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    private String cleanIdentifier(String value) {
        return normalizeIdentifier(value);
    }

    private String cleanupWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String unescapeSqlString(String value) {
        return value == null ? "" : value.replace("''", "'").trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String objectId(String sourceFile, int startLine, String qualifiedName) {
        return sourceFile + ":" + startLine + ":" + qualifiedName;
    }

    private int firstColon(String text) {
        int ascii = text.indexOf(':');
        int chinese = text.indexOf('：');
        if (ascii < 0) {
            return chinese;
        }
        if (chinese < 0) {
            return ascii;
        }
        return Math.min(ascii, chinese);
    }

    private String normalizeDocKey(String key) {
        return key.replaceAll("\\s+", "").trim();
    }

    private record NameParts(String schema, String name) {
    }

    private record TypeParts(String name, String arguments) {
    }

    private record SplitPart(String text, int offset) {
    }

    private record IdentifierToken(String value, int end) {
    }

    private record ProcedureStart(int start, int end, String rawName) {
    }
}
