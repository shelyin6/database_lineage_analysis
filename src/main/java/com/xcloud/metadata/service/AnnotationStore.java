package com.xcloud.metadata.service;

import com.xcloud.metadata.config.MetadataProperties;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AnnotationStore {
    private final Path databasePath;

    public AnnotationStore(MetadataProperties properties) {
        this.databasePath = properties.effectiveAnnotationDatabase();
    }

    @PostConstruct
    public void initialize() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS table_annotations (
                        schema_name TEXT NOT NULL,
                        table_name TEXT NOT NULL,
                        comment TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (schema_name, table_name)
                    )
                    """);
            addColumnIfMissing(statement, "table_annotations", "chinese_name TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(statement, "table_annotations", "date_column TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(statement, "table_annotations", "load_mode TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(statement, "table_annotations", "table_type TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(statement, "table_annotations", "table_status TEXT NOT NULL DEFAULT '有效'");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS column_code_values (
                        schema_name TEXT NOT NULL,
                        table_name TEXT NOT NULL,
                        column_name TEXT NOT NULL,
                        code_value TEXT NOT NULL,
                        code_label TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (schema_name, table_name, column_name, code_value)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS column_code_configs (
                        schema_name TEXT NOT NULL,
                        table_name TEXT NOT NULL,
                        column_name TEXT NOT NULL,
                        source_type TEXT NOT NULL,
                        code_set TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY (schema_name, table_name, column_name)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS general_code_sets (
                        code_set TEXT PRIMARY KEY,
                        code_name TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS general_code_values (
                        code_set TEXT NOT NULL,
                        code_value TEXT NOT NULL,
                        code_label TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (code_set, code_value)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS login_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL,
                        display_name TEXT NOT NULL DEFAULT '',
                        success INTEGER NOT NULL,
                        failure_reason TEXT NOT NULL DEFAULT '',
                        ip_address TEXT NOT NULL DEFAULT '',
                        user_agent TEXT NOT NULL DEFAULT '',
                        logged_in_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_login_logs_logged_in_at
                    ON login_logs (logged_in_at DESC)
                    """);
            initializeCustomGroupingSchema(connection, statement);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS adjustment_marks (
                        object_type TEXT NOT NULL,
                        object_key TEXT NOT NULL,
                        object_name TEXT NOT NULL DEFAULT '',
                        note TEXT NOT NULL DEFAULT '',
                        marked_by TEXT NOT NULL DEFAULT '',
                        marked_at TEXT NOT NULL,
                        PRIMARY KEY (object_type, object_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_adjustment_marks_marked_at
                    ON adjustment_marks (marked_at DESC)
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize SQLite annotation database", exception);
        }
    }

    private void initializeCustomGroupingSchema(Connection connection, Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS custom_group_themes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    theme_name TEXT NOT NULL UNIQUE,
                    created_by TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        String now = Instant.now().toString();
        try (PreparedStatement defaultTheme = connection.prepareStatement("""
                INSERT OR IGNORE INTO custom_group_themes
                (id, theme_name, created_by, created_at, updated_at)
                VALUES (1, '默认主题', '', ?, ?)
                """)) {
            defaultTheme.setString(1, now);
            defaultTheme.setString(2, now);
            defaultTheme.executeUpdate();
        }

        if (!tableExists(connection, "custom_groups")) {
            createCustomGroupsTable(statement);
        } else if (!columnExists(connection, "custom_groups", "theme_id")) {
            statement.executeUpdate("ALTER TABLE custom_groups RENAME TO custom_groups_legacy");
            createCustomGroupsTable(statement);
            statement.executeUpdate("""
                    INSERT INTO custom_groups
                    (id, theme_id, object_type, group_name, parent_id, created_by, created_at, updated_at)
                    SELECT id, 1, object_type, group_name, parent_id, created_by, created_at, updated_at
                    FROM custom_groups_legacy
                    """);
            statement.executeUpdate("DROP TABLE custom_groups_legacy");
        }
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_custom_groups_theme_parent
                ON custom_groups (theme_id, parent_id)
                """);

        if (!tableExists(connection, "custom_group_members")) {
            createCustomGroupMembersTable(statement);
        } else if (!columnExists(connection, "custom_group_members", "theme_id")) {
            statement.executeUpdate("ALTER TABLE custom_group_members RENAME TO custom_group_members_legacy");
            createCustomGroupMembersTable(statement);
            statement.executeUpdate("""
                    INSERT INTO custom_group_members
                    (theme_id, object_type, object_key, group_id, updated_by, updated_at)
                    SELECT COALESCE(g.theme_id, 1), m.object_type, m.object_key, m.group_id, m.updated_by, m.updated_at
                    FROM custom_group_members_legacy m
                    LEFT JOIN custom_groups g ON g.id = m.group_id
                    """);
            statement.executeUpdate("DROP TABLE custom_group_members_legacy");
        }
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_custom_group_members_theme_group
                ON custom_group_members (theme_id, group_id)
                """);
    }

    private static void createCustomGroupsTable(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS custom_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    theme_id INTEGER NOT NULL,
                    object_type TEXT NOT NULL,
                    group_name TEXT NOT NULL,
                    parent_id INTEGER,
                    created_by TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE (theme_id, object_type, group_name)
                )
                """);
    }

    private static void createCustomGroupMembersTable(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS custom_group_members (
                    theme_id INTEGER NOT NULL,
                    object_type TEXT NOT NULL,
                    object_key TEXT NOT NULL,
                    group_id INTEGER NOT NULL,
                    updated_by TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (theme_id, object_type, object_key)
                )
                """);
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (columnName.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }

    public Map<String, String> tableComments() {
        Map<String, String> comments = new LinkedHashMap<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT schema_name, table_name, comment FROM table_annotations ORDER BY schema_name, table_name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                comments.put(key(result.getString(1), result.getString(2)), result.getString(3));
            }
            return comments;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read table annotations", exception);
        }
    }

    public String tableComment(String schema, String table) {
        return tableComments().getOrDefault(key(schema, table), "");
    }

    public TableProfile tableProfile(String schema, String table) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT comment, chinese_name, date_column, load_mode, table_type, table_status
                FROM table_annotations WHERE schema_name = ? AND table_name = ?
                """)) {
            statement.setString(1, normalizePart(schema));
            statement.setString(2, normalizePart(table));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return TableProfile.empty();
                return new TableProfile(result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read table profile", exception);
        }
    }

    public Map<String, TableProfile> tableProfiles() {
        Map<String, TableProfile> profiles = new LinkedHashMap<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT schema_name, table_name, comment, chinese_name, date_column, load_mode, table_type, table_status
                FROM table_annotations
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                profiles.put(key(result.getString(1), result.getString(2)), new TableProfile(
                        result.getString(3), result.getString(4), result.getString(5), result.getString(6),
                        result.getString(7), result.getString(8)));
            }
            return profiles;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read table profiles", exception);
        }
    }

    public void saveTableProfile(String schema, String table, TableProfile profile) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO table_annotations
                (schema_name, table_name, comment, chinese_name, date_column, load_mode, table_type, table_status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(schema_name, table_name) DO UPDATE SET
                    comment = excluded.comment, chinese_name = excluded.chinese_name, date_column = excluded.date_column,
                    load_mode = excluded.load_mode, table_type = excluded.table_type, table_status = excluded.table_status,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, normalizePart(schema));
            statement.setString(2, normalizePart(table));
            statement.setString(3, clean(profile.comment()));
            statement.setString(4, clean(profile.chineseName()));
            statement.setString(5, clean(profile.dateColumn()));
            statement.setString(6, clean(profile.loadMode()));
            statement.setString(7, clean(profile.tableType()));
            statement.setString(8, clean(profile.tableStatus()).isEmpty() ? "有效" : clean(profile.tableStatus()));
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save table profile", exception);
        }
    }

    public void saveTableComment(String schema, String table, String comment) {
        String normalizedSchema = normalizePart(schema);
        String normalizedTable = normalizePart(table);
        String value = comment == null ? "" : comment.trim();
        try (Connection connection = connect()) {
            if (value.isEmpty()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM table_annotations WHERE schema_name = ? AND table_name = ?")) {
                    statement.setString(1, normalizedSchema);
                    statement.setString(2, normalizedTable);
                    statement.executeUpdate();
                }
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO table_annotations (schema_name, table_name, comment, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(schema_name, table_name) DO UPDATE SET
                        comment = excluded.comment,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, normalizedSchema);
                statement.setString(2, normalizedTable);
                statement.setString(3, value);
                statement.setString(4, Instant.now().toString());
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save table annotation", exception);
        }
    }

    public Map<String, List<CodeValue>> codeValues(String schema, String table) {
        Map<String, List<CodeValue>> values = new LinkedHashMap<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, code_value, code_label
                FROM column_code_values
                WHERE schema_name = ? AND table_name = ?
                ORDER BY column_name, code_value
                """)) {
            statement.setString(1, normalizePart(schema));
            statement.setString(2, normalizePart(table));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.computeIfAbsent(result.getString(1), ignored -> new ArrayList<>())
                            .add(new CodeValue(result.getString(2), result.getString(3)));
                }
            }
            return values;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read column code values", exception);
        }
    }

    public Map<String, ColumnDictionary> dictionaries(String schema, String table) {
        Map<String, List<CodeValue>> manual = codeValues(schema, table);
        Map<String, ColumnDictionary> dictionaries = new LinkedHashMap<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, source_type, code_set FROM column_code_configs
                WHERE schema_name = ? AND table_name = ?
                """)) {
            statement.setString(1, normalizePart(schema));
            statement.setString(2, normalizePart(table));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String column = result.getString(1);
                    String source = result.getString(2);
                    String codeSet = result.getString(3);
                    List<CodeValue> values = "GENERAL".equals(source) ? generalCodeValues(codeSet)
                            : manual.getOrDefault(column, List.of());
                    dictionaries.put(column, new ColumnDictionary(source, codeSet, values));
                }
            }
            manual.forEach((column, values) -> dictionaries.putIfAbsent(column, new ColumnDictionary("MANUAL", "", values)));
            return dictionaries;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read column dictionaries", exception);
        }
    }

    public void replaceCodeValues(String schema, String table, String column, List<CodeValue> values) {
        String normalizedSchema = normalizePart(schema);
        String normalizedTable = normalizePart(table);
        String normalizedColumn = normalizePart(column);
        List<CodeValue> cleaned = values == null ? List.of() : values.stream()
                .filter(value -> value != null && value.value() != null && !value.value().trim().isEmpty())
                .map(value -> new CodeValue(value.value().trim(), value.label() == null ? "" : value.label().trim()))
                .toList();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM column_code_values
                    WHERE schema_name = ? AND table_name = ? AND column_name = ?
                    """)) {
                delete.setString(1, normalizedSchema);
                delete.setString(2, normalizedTable);
                delete.setString(3, normalizedColumn);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO column_code_values
                    (schema_name, table_name, column_name, code_value, code_label, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                for (CodeValue value : cleaned) {
                    insert.setString(1, normalizedSchema);
                    insert.setString(2, normalizedTable);
                    insert.setString(3, normalizedColumn);
                    insert.setString(4, value.value());
                    insert.setString(5, value.label());
                    insert.setString(6, Instant.now().toString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save column code values", exception);
        }
    }

    public void configureGeneralDictionary(String schema, String table, String column, String codeSet) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO column_code_configs (schema_name, table_name, column_name, source_type, code_set)
                VALUES (?, ?, ?, 'GENERAL', ?)
                ON CONFLICT(schema_name, table_name, column_name) DO UPDATE SET source_type = 'GENERAL', code_set = excluded.code_set
                """)) {
            statement.setString(1, normalizePart(schema)); statement.setString(2, normalizePart(table));
            statement.setString(3, normalizePart(column)); statement.setString(4, normalizePart(codeSet));
            statement.executeUpdate();
        } catch (SQLException exception) { throw new IllegalStateException("Unable to configure general dictionary", exception); }
    }

    public void configureManualDictionary(String schema, String table, String column) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO column_code_configs (schema_name, table_name, column_name, source_type, code_set)
                VALUES (?, ?, ?, 'MANUAL', '')
                ON CONFLICT(schema_name, table_name, column_name) DO UPDATE SET source_type = 'MANUAL', code_set = ''
                """)) {
            statement.setString(1, normalizePart(schema)); statement.setString(2, normalizePart(table)); statement.setString(3, normalizePart(column));
            statement.executeUpdate();
        } catch (SQLException exception) { throw new IllegalStateException("Unable to configure manual dictionary", exception); }
    }

    public List<GeneralCodeSet> generalCodeSets() {
        List<GeneralCodeSet> sets = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT code_set, code_name FROM general_code_sets ORDER BY code_set"); ResultSet result = statement.executeQuery()) {
            while (result.next()) sets.add(new GeneralCodeSet(result.getString(1), result.getString(2), generalCodeValues(result.getString(1))));
            return sets;
        } catch (SQLException exception) { throw new IllegalStateException("Unable to read general dictionaries", exception); }
    }

    public List<CodeValue> generalCodeValues(String codeSet) {
        List<CodeValue> values = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT code_value, code_label FROM general_code_values WHERE code_set = ? ORDER BY code_value")) {
            statement.setString(1, normalizePart(codeSet));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new CodeValue(result.getString(1), result.getString(2)));
            }
            return values;
        } catch (SQLException exception) { throw new IllegalStateException("Unable to read general code values", exception); }
    }

    public void saveGeneralCodeSet(String codeSet, String name, List<CodeValue> values) {
        String set = normalizePart(codeSet);
        if (set.isBlank()) throw new IllegalArgumentException("Code set is required");
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO general_code_sets (code_set, code_name, updated_at) VALUES (?, ?, ?)
                    ON CONFLICT(code_set) DO UPDATE SET code_name = excluded.code_name, updated_at = excluded.updated_at
                    """)) {
                upsert.setString(1, set); upsert.setString(2, clean(name)); upsert.setString(3, Instant.now().toString()); upsert.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM general_code_values WHERE code_set = ?")) {
                delete.setString(1, set); delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO general_code_values (code_set, code_value, code_label, updated_at) VALUES (?, ?, ?, ?)
                    """)) {
                for (CodeValue value : values == null ? List.<CodeValue>of() : values) {
                    if (value == null || clean(value.value()).isEmpty()) continue;
                    insert.setString(1, set); insert.setString(2, clean(value.value())); insert.setString(3, clean(value.label())); insert.setString(4, Instant.now().toString()); insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) { throw new IllegalStateException("Unable to save general dictionary", exception); }
    }

    public void recordLogin(LoginLog log) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO login_logs
                (username, display_name, success, failure_reason, ip_address, user_agent, logged_in_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, clean(log.username()));
            statement.setString(2, clean(log.displayName()));
            statement.setInt(3, log.success() ? 1 : 0);
            statement.setString(4, limited(log.failureReason(), 300));
            statement.setString(5, limited(log.ipAddress(), 120));
            statement.setString(6, limited(log.userAgent(), 1000));
            statement.setString(7, log.loggedInAt() == null || log.loggedInAt().isBlank() ? Instant.now().toString() : log.loggedInAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to write login log", exception);
        }
    }

    public List<LoginLog> loginLogs(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        List<LoginLog> logs = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, username, display_name, success, failure_reason, ip_address, user_agent, logged_in_at
                FROM login_logs
                ORDER BY logged_in_at DESC, id DESC
                LIMIT ?
                """)) {
            statement.setInt(1, boundedLimit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    logs.add(new LoginLog(
                            result.getLong(1), result.getString(2), result.getString(3), result.getInt(4) == 1,
                            result.getString(5), result.getString(6), result.getString(7), result.getString(8)
                    ));
                }
            }
            return logs;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read login logs", exception);
        }
    }

    public long defaultCustomGroupThemeId() {
        return 1L;
    }

    public boolean customGroupThemeExists(long themeId) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM custom_group_themes WHERE id = ?")) {
            statement.setLong(1, themeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to check custom group theme", exception);
        }
    }

    public List<CustomGroupTheme> customGroupThemes() {
        List<CustomGroupTheme> themes = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT t.id, t.theme_name, t.created_by, t.created_at, t.updated_at, COUNT(g.id)
                FROM custom_group_themes t
                LEFT JOIN custom_groups g ON g.theme_id = t.id
                GROUP BY t.id, t.theme_name, t.created_by, t.created_at, t.updated_at
                ORDER BY t.id
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                themes.add(new CustomGroupTheme(
                        result.getLong(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getInt(6)
                ));
            }
            return themes;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read custom group themes", exception);
        }
    }

    public CustomGroupTheme createCustomGroupTheme(String themeName, String username) {
        String name = limited(themeName, 120);
        if (name.isBlank()) throw new IllegalArgumentException("Theme name is required");
        String now = Instant.now().toString();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO custom_group_themes (theme_name, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(theme_name) DO UPDATE SET updated_at = excluded.updated_at
                """)) {
            statement.setString(1, name);
            statement.setString(2, clean(username));
            statement.setString(3, now);
            statement.setString(4, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save custom group theme", exception);
        }
        return customGroupThemes().stream()
                .filter(theme -> name.equals(theme.themeName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Custom group theme was not created"));
    }

    public void deleteCustomGroupTheme(long themeId) {
        if (themeId <= 0 || themeId == defaultCustomGroupThemeId()) {
            throw new IllegalArgumentException("The default custom group theme cannot be deleted");
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement members = connection.prepareStatement(
                    "DELETE FROM custom_group_members WHERE theme_id = ?");
                 PreparedStatement groups = connection.prepareStatement(
                         "DELETE FROM custom_groups WHERE theme_id = ?");
                 PreparedStatement theme = connection.prepareStatement(
                         "DELETE FROM custom_group_themes WHERE id = ?")) {
                members.setLong(1, themeId);
                members.executeUpdate();
                groups.setLong(1, themeId);
                groups.executeUpdate();
                theme.setLong(1, themeId);
                if (theme.executeUpdate() == 0) {
                    throw new IllegalArgumentException("Custom group theme does not exist");
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to delete custom group theme", exception);
        }
    }

    public List<CustomGroup> customGroups() {
        return customGroups(defaultCustomGroupThemeId());
    }

    public List<CustomGroup> customGroups(long themeId) {
        List<CustomGroup> groups = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT g.id, g.object_type, g.group_name, g.parent_id, g.created_by, g.created_at, g.updated_at,
                       g.theme_id, COUNT(m.object_key)
                FROM custom_groups g
                LEFT JOIN custom_group_members m ON m.theme_id = g.theme_id AND m.group_id = g.id
                WHERE g.theme_id = ?
                GROUP BY g.id, g.theme_id, g.object_type, g.group_name, g.parent_id, g.created_by, g.created_at, g.updated_at
                ORDER BY g.object_type, g.group_name
                """)) {
            statement.setLong(1, themeId);
            try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                groups.add(new CustomGroup(
                        result.getLong(1), result.getLong(8), result.getString(2), result.getString(3),
                        result.getObject(4) == null ? null : result.getLong(4), result.getString(5), result.getString(6),
                        result.getString(7), result.getInt(9)
                ));
            }
            return groups;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read custom groups", exception);
        }
    }

    public CustomGroup createCustomGroup(String objectType, String groupName, Long parentId, String username) {
        return createCustomGroup(defaultCustomGroupThemeId(), objectType, groupName, parentId, username);
    }

    public CustomGroup createCustomGroup(long themeId, String objectType, String groupName, Long parentId, String username) {
        if (!customGroupThemeExists(themeId)) {
            throw new IllegalArgumentException("Custom group theme does not exist");
        }
        String type = normalizeObjectType(objectType);
        String name = limited(groupName, 120);
        if (name.isBlank()) throw new IllegalArgumentException("Group name is required");
        String now = Instant.now().toString();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO custom_groups (theme_id, object_type, group_name, parent_id, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(theme_id, object_type, group_name) DO UPDATE SET
                    parent_id = excluded.parent_id,
                    updated_at = excluded.updated_at
                """)) {
            validateCustomGroupParent(connection, themeId, type, name, parentId);
            statement.setLong(1, themeId);
            statement.setString(2, type);
            statement.setString(3, name);
            if (parentId == null || parentId <= 0) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setLong(4, parentId);
            }
            statement.setString(5, clean(username));
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save custom group", exception);
        }
        return customGroups(themeId).stream()
                .filter(group -> type.equals(group.objectType()) && name.equals(group.groupName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Custom group was not created"));
    }

    public void deleteCustomGroup(long groupId) {
        deleteCustomGroup(defaultCustomGroupThemeId(), groupId);
    }

    public void deleteCustomGroup(long themeId, long groupId) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement children = connection.prepareStatement(
                    "UPDATE custom_groups SET parent_id = NULL WHERE theme_id = ? AND parent_id = ?");
                 PreparedStatement members = connection.prepareStatement(
                         "DELETE FROM custom_group_members WHERE theme_id = ? AND group_id = ?");
                 PreparedStatement group = connection.prepareStatement(
                         "DELETE FROM custom_groups WHERE theme_id = ? AND id = ?")) {
                children.setLong(1, themeId);
                children.setLong(2, groupId);
                children.executeUpdate();
                members.setLong(1, themeId);
                members.setLong(2, groupId);
                members.executeUpdate();
                group.setLong(1, themeId);
                group.setLong(2, groupId);
                group.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to delete custom group", exception);
        }
    }

    public void assignCustomGroup(String objectType, String objectKey, long groupId, String username) {
        assignCustomGroup(defaultCustomGroupThemeId(), objectType, objectKey, groupId, username);
    }

    public void assignCustomGroup(long themeId, String objectType, String objectKey, long groupId, String username) {
        if (!customGroupThemeExists(themeId)) {
            throw new IllegalArgumentException("Custom group theme does not exist");
        }
        String type = normalizeObjectType(objectType);
        String key = normalizeObjectKey(objectKey);
        if (key.isBlank()) throw new IllegalArgumentException("Object key is required");
        String now = Instant.now().toString();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            if (groupId <= 0) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM custom_group_members WHERE theme_id = ? AND object_type = ? AND object_key = ?
                        """)) {
                    statement.setLong(1, themeId);
                    statement.setString(2, type);
                    statement.setString(3, key);
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement verify = connection.prepareStatement("""
                        SELECT id FROM custom_groups WHERE id = ? AND theme_id = ? AND object_type = ?
                        """)) {
                    verify.setLong(1, groupId);
                    verify.setLong(2, themeId);
                    verify.setString(3, type);
                    try (ResultSet result = verify.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("Custom group does not match object type");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO custom_group_members
                        (theme_id, object_type, object_key, group_id, updated_by, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(theme_id, object_type, object_key) DO UPDATE SET
                            group_id = excluded.group_id, updated_by = excluded.updated_by, updated_at = excluded.updated_at
                        """)) {
                    statement.setLong(1, themeId);
                    statement.setString(2, type);
                    statement.setString(3, key);
                    statement.setLong(4, groupId);
                    statement.setString(5, clean(username));
                    statement.setString(6, now);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to assign custom group", exception);
        }
    }

    public Map<String, CustomGroupAssignment> customGroupAssignments() {
        return customGroupAssignments(defaultCustomGroupThemeId());
    }

    public Map<String, CustomGroupAssignment> customGroupAssignments(long themeId) {
        Map<String, CustomGroupAssignment> assignments = new LinkedHashMap<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT g.id, g.theme_id, m.object_type, m.object_key, g.group_name
                FROM custom_group_members m
                JOIN custom_groups g ON g.id = m.group_id
                WHERE m.theme_id = ? AND g.theme_id = ?
                ORDER BY m.object_type, m.object_key
                """)) {
            statement.setLong(1, themeId);
            statement.setLong(2, themeId);
            try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                CustomGroupAssignment assignment = new CustomGroupAssignment(
                        result.getLong(1), result.getLong(2), result.getString(3), result.getString(4), result.getString(5)
                );
                assignments.put(assignmentKey(assignment.objectType(), assignment.objectKey()), assignment);
            }
            return assignments;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read custom group assignments", exception);
        }
    }

    public List<AdjustmentMark> adjustmentMarks() {
        List<AdjustmentMark> marks = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT object_type, object_key, object_name, note, marked_by, marked_at
                FROM adjustment_marks
                ORDER BY marked_at DESC
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                marks.add(new AdjustmentMark(
                        result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6)
                ));
            }
            return marks;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read adjustment marks", exception);
        }
    }

    public AdjustmentMark saveAdjustmentMark(
            String objectType, String objectKey, String objectName, String note, String username, boolean marked
    ) {
        String type = normalizeObjectType(objectType);
        String key = normalizeObjectKey(objectKey);
        if (key.isBlank()) throw new IllegalArgumentException("Object key is required");
        try (Connection connection = connect()) {
            if (!marked) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM adjustment_marks WHERE object_type = ? AND object_key = ?
                        """)) {
                    statement.setString(1, type);
                    statement.setString(2, key);
                    statement.executeUpdate();
                }
                return null;
            }
            String now = Instant.now().toString();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO adjustment_marks
                    (object_type, object_key, object_name, note, marked_by, marked_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(object_type, object_key) DO UPDATE SET
                        object_name = excluded.object_name, note = excluded.note,
                        marked_by = excluded.marked_by, marked_at = excluded.marked_at
                    """)) {
                statement.setString(1, type);
                statement.setString(2, key);
                statement.setString(3, limited(objectName, 240));
                statement.setString(4, limited(note, 1000));
                statement.setString(5, clean(username));
                statement.setString(6, now);
                statement.executeUpdate();
            }
            return adjustmentMarks().stream()
                    .filter(mark -> type.equals(mark.objectType()) && key.equals(mark.objectKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Adjustment mark was not saved"));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save adjustment mark", exception);
        }
    }

    public static String assignmentKey(String objectType, String objectKey) {
        return normalizeObjectType(objectType) + ":" + normalizeObjectKey(objectKey);
    }

    private static String normalizeObjectType(String objectType) {
        String type = normalizePart(objectType);
        if (!"TABLE".equals(type) && !"PROCEDURE".equals(type)) {
            throw new IllegalArgumentException("Object type must be TABLE or PROCEDURE");
        }
        return type;
    }

    private static String normalizeObjectKey(String objectKey) {
        return objectKey == null ? "" : objectKey.replace("\"", "").replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static void addColumnIfMissing(Statement statement, String table, String definition) throws SQLException {
        try { statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + definition); }
        catch (SQLException ignored) { }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String limited(String value, int maximumLength) {
        String cleaned = clean(value);
        return cleaned.length() <= maximumLength ? cleaned : cleaned.substring(0, maximumLength);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private static String key(String schema, String table) {
        return normalizePart(schema) + "." + normalizePart(table);
    }

    private static String normalizePart(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public record CodeValue(String value, String label) {
    }

    public record TableProfile(String comment, String chineseName, String dateColumn, String loadMode, String tableType, String tableStatus) {
        public static TableProfile empty() { return new TableProfile("", "", "", "", "", "有效"); }
    }

    public record ColumnDictionary(String sourceType, String codeSet, List<CodeValue> values) {
    }

    public record GeneralCodeSet(String codeSet, String name, List<CodeValue> values) {
    }

    public record LoginLog(
            long id,
            String username,
            String displayName,
            boolean success,
            String failureReason,
            String ipAddress,
            String userAgent,
            String loggedInAt
        ) {
    }

    public record CustomGroupTheme(
            long id,
            String themeName,
            String createdBy,
            String createdAt,
            String updatedAt,
            int groupCount
    ) {
    }

    public record CustomGroup(
            long id,
            long themeId,
            String objectType,
            String groupName,
            Long parentId,
            String createdBy,
            String createdAt,
            String updatedAt,
            int memberCount
        ) {
    }

    public record CustomGroupAssignment(long id, long themeId, String objectType, String objectKey, String groupName) {
    }

    public record AdjustmentMark(
            String objectType,
            String objectKey,
            String objectName,
            String note,
            String markedBy,
            String markedAt
    ) {
    }

    private static void validateCustomGroupParent(
            Connection connection, long themeId, String objectType, String groupName, Long parentId
    )
            throws SQLException {
        if (parentId == null || parentId <= 0) return;
        Long currentGroupId = null;
        try (PreparedStatement current = connection.prepareStatement("""
                SELECT id FROM custom_groups WHERE theme_id = ? AND object_type = ? AND group_name = ?
                """)) {
            current.setLong(1, themeId);
            current.setString(2, objectType);
            current.setString(3, groupName);
            try (ResultSet result = current.executeQuery()) {
                if (result.next()) currentGroupId = result.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT group_name FROM custom_groups WHERE id = ? AND theme_id = ? AND object_type = ?
                """)) {
            statement.setLong(1, parentId);
            statement.setLong(2, themeId);
            statement.setString(3, objectType);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Parent custom group does not match object type");
                }
            }
        }
        if (currentGroupId != null && currentGroupId.equals(parentId)) {
            throw new IllegalArgumentException("A custom group cannot be its own parent");
        }

        Long ancestorId = parentId;
        java.util.Set<Long> visited = new java.util.HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT parent_id FROM custom_groups WHERE id = ? AND theme_id = ? AND object_type = ?
                """)) {
            while (ancestorId != null && visited.add(ancestorId)) {
                statement.setLong(1, ancestorId);
                statement.setLong(2, themeId);
                statement.setString(3, objectType);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) break;
                    Object value = result.getObject(1);
                    ancestorId = value == null ? null : result.getLong(1);
                }
                if (currentGroupId != null && currentGroupId.equals(ancestorId)) {
                    throw new IllegalArgumentException("Custom group hierarchy cannot contain a cycle");
                }
            }
            if (ancestorId != null) {
                throw new IllegalArgumentException("Custom group hierarchy cannot contain a cycle");
            }
        }
    }
}
