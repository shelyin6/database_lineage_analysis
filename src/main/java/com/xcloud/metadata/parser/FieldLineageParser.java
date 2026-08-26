package com.xcloud.metadata.parser;

import com.xcloud.metadata.model.ColumnLineage;
import com.xcloud.metadata.model.ColumnMetadata;
import com.xcloud.metadata.model.ProcedureMetadata;
import com.xcloud.metadata.model.TableMetadata;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts only unambiguous direct column assignments from INSERT ... SELECT statements.
 * Expressions that need calculation are deliberately ignored.
 */
public final class FieldLineageParser {
    private static final Pattern INSERT_PATTERN = Pattern.compile(
            "\\bINSERT\\s+(?:OVERWRITE\\s+)?INTO\\s+(?:TABLE\\s+)?"
                    + "((?:\"[^\"]+\"|[A-Z0-9_$]+)(?:\\s*\\.\\s*(?:\"[^\"]+\"|[A-Z0-9_$]+))?)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DIRECT_REFERENCE = Pattern.compile(
            "^[A-Z0-9_$#]+(?:\\.[A-Z0-9_$#]+){0,2}$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AS_ALIAS = Pattern.compile(
            "^(.*?)(?:\\s+AS\\s+)([\"A-Z0-9_$#]+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BARE_ALIAS = Pattern.compile(
            "^(.*\\S)\\s+([\"A-Z0-9_$#]+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Set<String> RELATION_STOP_WORDS = Set.of(
            "AS", "ON", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "LIMIT", "QUALIFY",
            "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "JOIN", "FROM", "AND", "OR"
    );

    public List<ColumnLineage> parse(
            ProcedureMetadata procedure,
            Map<String, TableMetadata> tablesByQualifiedName
    ) {
        if (procedure == null || procedure.rawSql() == null || procedure.rawSql().isBlank()) {
            return List.of();
        }

        String sql = procedure.rawSql();
        String searchableSql = maskCommentsAndStringLiterals(sql);
        Matcher matcher = INSERT_PATTERN.matcher(searchableSql);
        Map<String, ColumnLineage> unique = new LinkedHashMap<>();
        while (matcher.find()) {
            int statementEnd = findStatementEnd(sql, matcher.start());
            int statementEndExclusive = Math.min(sql.length(), statementEnd + 1);
            String targetTable = normalizeObjectName(matcher.group(1), procedure.schema());
            int cursor = skipWhitespace(sql, matcher.end());

            List<String> targetColumns = List.of();
            if (cursor < statementEndExclusive && sql.charAt(cursor) == '(') {
                int close = findMatchingParen(sql, cursor);
                if (close > cursor && close < statementEndExclusive) {
                    targetColumns = splitTopLevel(sql.substring(cursor + 1, close), ',').stream()
                            .map(part -> firstIdentifier(stripComments(part.text())))
                            .filter(value -> !value.isBlank())
                            .map(this::normalizeIdentifier)
                            .toList();
                    cursor = close + 1;
                }
            }

            int selectStart = findTopLevelKeyword(sql, cursor, statementEndExclusive, "SELECT");
            if (selectStart < 0) {
                continue;
            }
            int fromStart = findTopLevelKeyword(sql, selectStart + "SELECT".length(), statementEndExclusive, "FROM");
            if (fromStart < 0) {
                continue;
            }

            if (targetColumns.isEmpty()) {
                targetColumns = tableColumns(targetTable, tablesByQualifiedName);
            }
            if (targetColumns.isEmpty()) {
                continue;
            }

            String query = sql.substring(cursor, statementEndExclusive);
            int querySelectStart = selectStart - cursor;
            int queryFromStart = fromStart - cursor;
            Map<String, String> ctes = extractCtes(query, procedure.schema());
            Map<String, Relation> relations = parseRelations(query, queryFromStart, procedure.schema(), ctes);
            List<SplitPart> projections = splitTopLevel(
                    sql.substring(selectStart + "SELECT".length(), fromStart),
                    ','
            );

            int count = Math.min(targetColumns.size(), projections.size());
            for (int index = 0; index < count; index++) {
                String targetColumn = targetColumns.get(index);
                SplitPart projection = projections.get(index);
                SourceColumn source = resolveDirect(
                        projection.text(),
                        relations,
                        ctes,
                        tablesByQualifiedName,
                        new LinkedHashSet<>()
                );
                if (source == null) {
                    continue;
                }
                int line = procedure.startLine()
                        + lineOf(sql, selectStart + "SELECT".length() + projection.offset())
                        - 1;
                ColumnLineage lineage = new ColumnLineage(
                        targetTable,
                        targetColumn,
                        source.table(),
                        source.column(),
                        procedure.id(),
                        procedure.qualifiedName(),
                        procedure.sourceFile(),
                        line
                );
                String key = normalizeIdentifier(targetTable) + "|" + targetColumn + "|"
                        + normalizeIdentifier(source.table()) + "|" + source.column() + "|"
                        + procedure.id() + "|" + line;
                unique.putIfAbsent(key, lineage);
            }
        }
        return List.copyOf(unique.values());
    }

    private SourceColumn resolveDirect(
            String rawExpression,
            Map<String, Relation> relations,
            Map<String, String> ctes,
            Map<String, TableMetadata> tablesByQualifiedName,
            Set<String> visiting
    ) {
        String expression = stripOuterParentheses(stripComments(rawExpression).trim());
        if (expression.isBlank()) {
            return null;
        }

        SourceColumn direct = resolveReference(expression, relations, tablesByQualifiedName, ctes, visiting);
        if (direct != null) {
            return direct;
        }

        Matcher asMatcher = AS_ALIAS.matcher(expression);
        if (asMatcher.matches()) {
            return resolveReference(
                    stripOuterParentheses(asMatcher.group(1).trim()),
                    relations,
                    tablesByQualifiedName,
                    ctes,
                    visiting
            );
        }

        Matcher bareMatcher = BARE_ALIAS.matcher(expression);
        if (bareMatcher.matches()) {
            return resolveReference(
                    stripOuterParentheses(bareMatcher.group(1).trim()),
                    relations,
                    tablesByQualifiedName,
                    ctes,
                    visiting
            );
        }
        return null;
    }

    private SourceColumn resolveReference(
            String expression,
            Map<String, Relation> relations,
            Map<String, TableMetadata> tablesByQualifiedName,
            Map<String, String> ctes,
            Set<String> visiting
    ) {
        String candidate = stripOuterParentheses(expression.trim());
        if (!DIRECT_REFERENCE.matcher(candidate).matches()) {
            return null;
        }

        String[] parts = normalizeIdentifier(candidate).split("\\.");
        String column = parts[parts.length - 1];
        if (parts.length == 1) {
            List<Relation> matches = relations.values().stream()
                    .filter(relation -> relationContainsColumn(relation, column, tablesByQualifiedName, ctes, visiting))
                    .distinct()
                    .toList();
            return matches.size() == 1
                    ? resolveRelationColumn(matches.get(0), column, tablesByQualifiedName, ctes, visiting)
                    : null;
        }

        String qualifier = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
        Relation relation = relations.get(normalizeIdentifier(qualifier));
        if (relation == null) {
            relation = relations.get(simpleName(qualifier));
        }
        if (relation == null && parts.length == 3) {
            String qualifiedTable = normalizeIdentifier(qualifier);
            if (tablesByQualifiedName.containsKey(qualifiedTable)) {
                return new SourceColumn(qualifiedTable, column);
            }
        }
        return relation == null
                ? null
                : resolveRelationColumn(relation, column, tablesByQualifiedName, ctes, visiting);
    }

    private SourceColumn resolveRelationColumn(
            Relation relation,
            String column,
            Map<String, TableMetadata> tablesByQualifiedName,
            Map<String, String> ctes,
            Set<String> visiting
    ) {
        if (relation.table() != null) {
            return new SourceColumn(relation.table(), column);
        }
        if (relation.subquery() == null) {
            return null;
        }
        String visitKey = relation.subquery();
        if (!visiting.add(visitKey)) {
            return null;
        }
        try {
            int selectStart = findTopLevelKeyword(relation.subquery(), 0, relation.subquery().length(), "SELECT");
            int fromStart = selectStart < 0
                    ? -1
                    : findTopLevelKeyword(relation.subquery(), selectStart + "SELECT".length(),
                    relation.subquery().length(), "FROM");
            if (selectStart < 0 || fromStart < 0) {
                return null;
            }
            Map<String, String> nestedCtes = extractCtes(relation.subquery(), "PUBLIC");
            Map<String, Relation> nestedRelations = parseRelations(
                    relation.subquery(),
                    fromStart,
                    "PUBLIC",
                    nestedCtes
            );
            for (SplitPart projection : splitTopLevel(
                    relation.subquery().substring(selectStart + "SELECT".length(), fromStart),
                    ','
            )) {
                Projection projectionInfo = projectionInfo(projection.text());
                if (!column.equalsIgnoreCase(projectionInfo.outputColumn())) {
                    continue;
                }
                return resolveDirect(
                        projectionInfo.expression(),
                        nestedRelations,
                        nestedCtes,
                        tablesByQualifiedName,
                        visiting
                );
            }
            return null;
        } finally {
            visiting.remove(visitKey);
        }
    }

    private boolean relationContainsColumn(
            Relation relation,
            String column,
            Map<String, TableMetadata> tablesByQualifiedName,
            Map<String, String> ctes,
            Set<String> visiting
    ) {
        if (relation.table() != null) {
            TableMetadata table = tablesByQualifiedName.get(relation.table());
            return table == null || table.columns().stream()
                    .anyMatch(item -> column.equalsIgnoreCase(item.name()));
        }
        return resolveRelationColumn(relation, column, tablesByQualifiedName, ctes, visiting) != null;
    }

    private Projection projectionInfo(String rawProjection) {
        String expression = stripOuterParentheses(stripComments(rawProjection).trim());
        Matcher asMatcher = AS_ALIAS.matcher(expression);
        if (asMatcher.matches()) {
            return new Projection(asMatcher.group(1).trim(), normalizeIdentifier(asMatcher.group(2)));
        }
        Matcher bareMatcher = BARE_ALIAS.matcher(expression);
        if (bareMatcher.matches() && DIRECT_REFERENCE.matcher(stripOuterParentheses(bareMatcher.group(1).trim())).matches()) {
            return new Projection(
                    bareMatcher.group(1).trim(),
                    normalizeIdentifier(bareMatcher.group(2))
            );
        }
        String outputColumn = DIRECT_REFERENCE.matcher(expression).matches()
                ? simpleName(expression)
                : "";
        return new Projection(expression, outputColumn);
    }

    private Map<String, Relation> parseRelations(
            String query,
            int fromStart,
            String defaultSchema,
            Map<String, String> ctes
    ) {
        Map<String, Relation> relations = new LinkedHashMap<>();
        int cursor = fromStart;
        while (cursor < query.length()) {
            Keyword keyword = findNextTopLevelKeyword(query, cursor, query.length(), Set.of("FROM", "JOIN"));
            if (keyword == null) {
                break;
            }
            int relationStart = skipWhitespace(query, keyword.end());
            if (relationStart >= query.length()) {
                break;
            }

            Relation relation;
            int relationEnd;
            if (query.charAt(relationStart) == '(') {
                int close = findMatchingParen(query, relationStart);
                if (close < 0) {
                    break;
                }
                int aliasStart = skipWhitespace(query, close + 1);
                if (matchesKeyword(query, aliasStart, "AS")) {
                    aliasStart = skipWhitespace(query, aliasStart + 2);
                }
                String alias = readIdentifier(query, aliasStart);
                relation = new Relation(null, query.substring(relationStart + 1, close), normalizeIdentifier(alias));
                relationEnd = alias.isBlank() ? close + 1 : aliasStart + alias.length();
            } else {
                IdentifierToken object = readQualifiedIdentifier(query, relationStart);
                if (object == null) {
                    cursor = keyword.end();
                    continue;
                }
                String objectName = normalizeObjectName(object.value(), defaultSchema);
                int aliasStart = skipWhitespace(query, object.end());
                String alias = "";
                if (matchesKeyword(query, aliasStart, "AS")) {
                    aliasStart = skipWhitespace(query, aliasStart + 2);
                    alias = readIdentifier(query, aliasStart);
                } else {
                    String possibleAlias = readIdentifier(query, aliasStart);
                    if (!possibleAlias.isBlank() && !RELATION_STOP_WORDS.contains(normalizeIdentifier(possibleAlias))) {
                        alias = possibleAlias;
                    }
                }
                String cte = ctes.get(simpleName(objectName));
                relation = cte == null
                        ? new Relation(objectName, null, normalizeIdentifier(alias))
                        : new Relation(null, cte, normalizeIdentifier(alias));
                relationEnd = alias.isBlank() ? object.end() : aliasStart + alias.length();
            }

            String alias = relation.alias();
            if (!alias.isBlank()) {
                relations.put(alias, relation);
            }
            if (relation.table() != null) {
                relations.putIfAbsent(simpleName(relation.table()), relation);
                relations.putIfAbsent(normalizeIdentifier(relation.table()), relation);
            }
            cursor = Math.max(keyword.end(), relationEnd);
        }
        return relations;
    }

    private Map<String, String> extractCtes(String query, String defaultSchema) {
        int withStart = findTopLevelKeyword(query, 0, query.length(), "WITH");
        if (withStart < 0 || !query.substring(0, withStart).isBlank()) {
            return Map.of();
        }

        Map<String, String> ctes = new LinkedHashMap<>();
        int cursor = skipWhitespace(query, withStart + "WITH".length());
        while (cursor < query.length()) {
            String name = readIdentifier(query, cursor);
            if (name.isBlank()) {
                break;
            }
            cursor = skipWhitespace(query, cursor + name.length());
            if (cursor < query.length() && query.charAt(cursor) == '(') {
                int closeColumns = findMatchingParen(query, cursor);
                if (closeColumns < 0) {
                    break;
                }
                cursor = skipWhitespace(query, closeColumns + 1);
            }
            if (!matchesKeyword(query, cursor, "AS")) {
                break;
            }
            cursor = skipWhitespace(query, cursor + 2);
            if (cursor >= query.length() || query.charAt(cursor) != '(') {
                break;
            }
            int close = findMatchingParen(query, cursor);
            if (close < 0) {
                break;
            }
            ctes.put(normalizeIdentifier(name), query.substring(cursor + 1, close));
            cursor = skipWhitespace(query, close + 1);
            if (cursor >= query.length() || query.charAt(cursor) != ',') {
                break;
            }
            cursor = skipWhitespace(query, cursor + 1);
        }
        return ctes;
    }

    private List<String> tableColumns(String targetTable, Map<String, TableMetadata> tablesByQualifiedName) {
        TableMetadata table = tablesByQualifiedName.get(normalizeIdentifier(targetTable));
        if (table == null || table.columns() == null) {
            return List.of();
        }
        return table.columns().stream().map(ColumnMetadata::name).map(this::normalizeIdentifier).toList();
    }

    private int findTopLevelKeyword(String text, int from, int endExclusive, String keyword) {
        int depth = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = Math.max(0, from); i < Math.min(text.length(), endExclusive); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') lineComment = false;
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
                if (c == '\'' && next == '\'') i++;
                else if (c == '\'') singleQuote = false;
                continue;
            }
            if (doubleQuote) {
                if (c == '"') doubleQuote = false;
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                singleQuote = true;
                continue;
            }
            if (c == '"') {
                doubleQuote = true;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && matchesKeyword(text, i, keyword)) {
                return i;
            }
        }
        return -1;
    }

    private Keyword findNextTopLevelKeyword(String text, int from, int endExclusive, Set<String> keywords) {
        for (int i = Math.max(0, from); i < Math.min(text.length(), endExclusive); i++) {
            for (String keyword : keywords) {
                if (matchesKeyword(text, i, keyword)
                        && findTopLevelKeyword(text, from, i + keyword.length(), keyword) == i) {
                    return new Keyword(keyword, i, i + keyword.length());
                }
            }
        }
        return null;
    }

    private int findStatementEnd(String sql, int from) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') lineComment = false;
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
                if (c == '\'' && next == '\'') i++;
                else if (c == '\'') singleQuote = false;
                continue;
            }
            if (doubleQuote) {
                if (c == '"') doubleQuote = false;
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
            } else if (c == ';') {
                return i;
            }
        }
        return Math.max(from, sql.length() - 1);
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
                if (c == '\n') lineComment = false;
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
                if (c == '\'' && next == '\'') i++;
                else if (c == '\'') singleQuote = false;
                continue;
            }
            if (doubleQuote) {
                if (c == '"') doubleQuote = false;
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
            } else if (c == ')' && --depth == 0) {
                return i;
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
                if (c == '\n') lineComment = false;
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
                if (c == '\'' && next == '\'') i++;
                else if (c == '\'') singleQuote = false;
                continue;
            }
            if (doubleQuote) {
                if (c == '"') doubleQuote = false;
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

    private String maskCommentsAndStringLiterals(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean singleQuote = false;
        boolean doubleQuote = false;
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
            if (doubleQuote) {
                result.append(c == '\n' ? '\n' : ' ');
                if (c == '"') doubleQuote = false;
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

    private String stripOuterParentheses(String value) {
        String result = value == null ? "" : value.trim();
        while (result.startsWith("(") && result.endsWith(")")) {
            int close = findMatchingParen(result, 0);
            if (close != result.length() - 1) break;
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private int skipWhitespace(String text, int offset) {
        int cursor = Math.max(0, offset);
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }

    private boolean matchesKeyword(String text, int offset, String keyword) {
        if (offset < 0 || offset + keyword.length() > text.length()
                || !text.regionMatches(true, offset, keyword, 0, keyword.length())) {
            return false;
        }
        int before = offset - 1;
        int after = offset + keyword.length();
        return (before < 0 || !isIdentifierChar(text.charAt(before)))
                && (after >= text.length() || !isIdentifierChar(text.charAt(after)));
    }

    private String readIdentifier(String text, int offset) {
        int cursor = skipWhitespace(text, offset);
        if (cursor >= text.length()) return "";
        if (text.charAt(cursor) == '"') {
            int end = text.indexOf('"', cursor + 1);
            return end > cursor ? text.substring(cursor, end + 1) : "";
        }
        int start = cursor;
        while (cursor < text.length() && isIdentifierChar(text.charAt(cursor))) cursor++;
        return cursor > start ? text.substring(start, cursor) : "";
    }

    private IdentifierToken readQualifiedIdentifier(String text, int offset) {
        int cursor = skipWhitespace(text, offset);
        String first = readIdentifier(text, cursor);
        if (first.isBlank()) return null;
        cursor += first.length();
        int dot = skipWhitespace(text, cursor);
        if (dot < text.length() && text.charAt(dot) == '.') {
            String second = readIdentifier(text, dot + 1);
            if (!second.isBlank()) {
                int end = skipWhitespace(text, dot + 1) + second.length();
                return new IdentifierToken(first + "." + second, end);
            }
        }
        return new IdentifierToken(first, cursor);
    }

    private String firstIdentifier(String value) {
        String text = value == null ? "" : value.stripLeading();
        if (text.isBlank()) return "";
        if (text.charAt(0) == '"') {
            int end = text.indexOf('"', 1);
            return end > 0 ? text.substring(0, end + 1) : text;
        }
        int end = 0;
        while (end < text.length() && isIdentifierChar(text.charAt(end))) end++;
        return text.substring(0, end);
    }

    private boolean isIdentifierChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#' || value == '"';
    }

    private String normalizeObjectName(String rawName, String defaultSchema) {
        String value = normalizeIdentifier(rawName);
        String[] parts = value.split("\\.");
        if (parts.length == 2) return parts[0] + "." + parts[1];
        return normalizeIdentifier(defaultSchema) + "." + value;
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.replace("\"", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String simpleName(String value) {
        String normalized = normalizeIdentifier(value);
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private int lineOf(String text, int offset) {
        int line = 1;
        int limit = Math.min(Math.max(0, offset), text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private record SplitPart(String text, int offset) {
    }

    private record IdentifierToken(String value, int end) {
    }

    private record Keyword(String value, int start, int end) {
    }

    private record Relation(String table, String subquery, String alias) {
    }

    private record Projection(String expression, String outputColumn) {
    }

    private record SourceColumn(String table, String column) {
    }
}
