package io.dm7codex.plugin.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionInitializer;
import io.dm7codex.plugin.release.ReleaseExportService;
import io.dm7codex.plugin.release.ReleaseLogService;
import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SqlPurpose;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateDatabaseTest {
    @TempDir
    Path tempDir;

    @Test
    void migrationV2CreatesRequiredSchemaAndConnectionPragmas() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);

        try (var database = StateDatabase.open(paths.stateDatabase());
                var connection = database.openConnection()) {
            assertEquals(2, intPragma(connection, "user_version"));
            assertEquals(1, intPragma(connection, "foreign_keys"));
            assertEquals(5_000, intPragma(connection, "busy_timeout"));
            assertEquals("wal", textPragma(connection, "journal_mode"));

            var tables = new HashSet<String>();
            try (var statement = connection.prepareStatement(
                            "SELECT name FROM sqlite_master WHERE type = 'table'");
                    var rows = statement.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }

            assertTrue(tables.containsAll(Set.of(
                    "logical_session",
                    "release_version",
                    "execution",
                    "statement_event",
                    "export_artifact")), tables::toString);

            assertColumns(connection, "release_version", Set.of(
                    "session_id", "version", "database_fingerprint", "active_sql", "status",
                    "statement_count", "first_sequence", "last_sequence", "sealed_source_path",
                    "sealed_source_sha256", "created_at", "sealed_at"));
            assertColumns(connection, "execution", Set.of(
                    "execution_id", "correlation_id", "session_id", "connection_fingerprint",
                    "source", "purpose", "sql_text", "phase", "status", "started_at",
                    "completed_at", "affected_row_count", "returned_row_count", "sql_state",
                    "error_code", "error_message", "recorded", "exclusion_reason"));
            assertColumns(connection, "statement_event", Set.of(
                    "event_id", "execution_id", "session_id", "release_version",
                    "statement_index", "sequence_number", "statement_kind", "status", "phase",
                    "row_count", "sql_state", "error_code", "recorded", "exclusion_reason",
                    "raw_sql", "replayable_sql", "created_at"));
            assertColumns(connection, "export_artifact", Set.of(
                    "export_id", "session_id", "version", "state", "artifact_path",
                    "artifact_sha256", "first_sequence", "last_sequence", "statement_count",
                    "created_at", "completed_at", "error_message"));
            assertNotNullColumns(connection, "release_version", Set.of(
                    "session_id", "version", "database_fingerprint", "active_sql", "status",
                    "statement_count", "created_at"));
            assertNotNullColumns(connection, "execution", Set.of(
                    "execution_id", "correlation_id", "session_id", "connection_fingerprint",
                    "source", "sql_text", "phase", "status", "started_at", "recorded"));
            assertNotNullColumns(connection, "statement_event", Set.of(
                    "session_id", "release_version", "statement_index", "statement_kind",
                    "status", "phase", "recorded", "raw_sql", "created_at"));
            assertNotNullColumns(connection, "export_artifact", Set.of(
                    "export_id", "session_id", "version", "state", "statement_count", "created_at"));

            assertForeignKey(connection, "statement_event", "execution", "execution_id", "execution_id");
            assertForeignKey(connection, "statement_event", "release_version", "session_id", "session_id");
            assertForeignKey(connection, "statement_event", "release_version", "release_version", "version");
            assertForeignKey(connection, "export_artifact", "release_version", "session_id", "session_id");
            assertForeignKey(connection, "export_artifact", "release_version", "version", "version");
            assertTrue(indexes(connection, "execution").contains("execution_by_session_started"));
            assertTrue(indexes(connection, "statement_event").contains("statement_event_by_execution"));
            assertTrue(indexes(connection, "export_artifact").contains("export_artifact_by_state"));
            try (var statement = connection.createStatement();
                    var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(violations.next());
            }
        }
    }

    @Test
    void sequenceRangesRejectHalfNullAndReverseValues() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(
                            paths, new SessionRepository(database, paths.sessionsDirectory()))
                    .initialize(new SessionIdentity(
                            "range-thread", "codex_thread", "verified"));
            try (var connection = database.openConnection()) {
                assertSqlRejected(connection, """
                        UPDATE release_version
                        SET first_sequence = 0, last_sequence = NULL
                        WHERE session_id = '%s' AND version = %d
                        """.formatted(session.sessionId(), session.version()));
                assertSqlRejected(connection, """
                        UPDATE release_version
                        SET first_sequence = 2, last_sequence = 1
                        WHERE session_id = '%s' AND version = %d
                        """.formatted(session.sessionId(), session.version()));
                assertSqlRejected(connection, """
                        INSERT INTO export_artifact(
                            export_id, session_id, version, state,
                            first_sequence, last_sequence, created_at
                        ) VALUES (
                            'half-null-export', '%s', %d, 'RECOVERY_REQUIRED',
                            0, NULL, '2026-07-10T00:00:00Z'
                        )
                        """.formatted(session.sessionId(), session.version()));
                assertSqlRejected(connection, """
                        INSERT INTO export_artifact(
                            export_id, session_id, version, state,
                            first_sequence, last_sequence, created_at
                        ) VALUES (
                            'reverse-export', '%s', %d, 'RECOVERY_REQUIRED',
                            2, 1, '2026-07-10T00:00:00Z'
                        )
                        """.formatted(session.sessionId(), session.version()));
            }
        }
    }

    @Test
    void concurrentOpensApplyMigrationExactlyOnce() throws Exception {
        var databasePath = RuntimePaths.forTest(tempDir).stateDatabase();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = executor.invokeAll(java.util.stream.IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<Void>>mapToObj(ignored -> () -> {
                        try (var ignoredDatabase = StateDatabase.open(databasePath)) {
                            return null;
                        }
                    })
                    .toList());
            for (var future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        try (var database = StateDatabase.open(databasePath);
                var connection = database.openConnection()) {
            assertEquals(2, intPragma(connection, "user_version"));
            assertEquals(5, countUserTables(connection));
        }
    }

    @Test
    void oldV1DatabaseMigratesIdempotentlyAndSupportsPendingRecoveryExport() throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve("old-v1"));
        createOldV1Database(paths.stateDatabase());

        try (var database = StateDatabase.open(paths.stateDatabase())) {
            try (var connection = database.openConnection()) {
                assertEquals(2, intPragma(connection, "user_version"));
                assertColumns(connection, "statement_event", Set.of(
                        "operation_id", "pending_fingerprint", "file_offset",
                        "block_sha256", "binding_comment"));
            }
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            var session = new SessionInitializer(paths, sessions)
                    .initialize(new SessionIdentity(
                            "old-v1-session", "test_override", "verified"));
            var failing = new ReleaseLogService(
                    paths, sessions, java.time.Duration.ofSeconds(2),
                    new io.dm7codex.plugin.sql.SqlSecurityPolicy(), stage -> {
                        if (stage == ReleaseLogService.RecordStage.AFTER_PARTIAL_APPEND) {
                            throw new java.io.IOException("injected");
                        }
                    });
            var parsed = new DmSqlParser().parse("CREATE TABLE V1_RECOVERY(ID INT)").get(0);
            assertThrows(java.io.IOException.class, () -> failing.recordCommitted(
                    session, "db-a", SqlPurpose.MIGRATION, "old-v1-operation",
                    parsed, "CREATE TABLE V1_RECOVERY(ID INT)"));
            var artifact = new ReleaseExportService(
                    paths, sessions, new ExportRepository(database)).export(session);
            assertEquals(1, artifact.statementCount());
            assertTrue(Files.readString(artifact.path())
                    .contains("CREATE TABLE V1_RECOVERY(ID INT);"));
        }

        try (var reopened = StateDatabase.open(paths.stateDatabase());
                var connection = reopened.openConnection()) {
            assertEquals(2, intPragma(connection, "user_version"));
        }
    }

    @Test
    void futureSchemaVersionIsRejected() throws Exception {
        var path = RuntimePaths.forTest(tempDir.resolve("future-version")).stateDatabase();
        try (var database = StateDatabase.open(path);
                var connection = database.openConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 99");
            }
        }
        assertThrows(SQLException.class, () -> StateDatabase.open(path));
    }

    private static void createOldV1Database(Path path) throws Exception {
        try (var database = StateDatabase.open(path);
                var connection = database.openConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS statement_event_operation_id");
            statement.execute("ALTER TABLE statement_event DROP COLUMN binding_comment");
            statement.execute("ALTER TABLE statement_event DROP COLUMN block_sha256");
            statement.execute("ALTER TABLE statement_event DROP COLUMN file_offset");
            statement.execute("ALTER TABLE statement_event DROP COLUMN pending_fingerprint");
            statement.execute("ALTER TABLE statement_event DROP COLUMN operation_id");
            statement.execute("PRAGMA user_version = 1");
        }
    }

    @Test
    void independentJvmWaitsForMigrationLockReleasedByAbnormalExit() throws Exception {
        var databasePath = RuntimePaths.forTest(tempDir).stateDatabase();
        var readyPath = tempDir.resolve("holder.ready");
        var holder = startProbe("hold-and-halt", databasePath, readyPath);
        Process opener = null;
        try {
            awaitFile(readyPath);
            opener = startProbe("open", databasePath, readyPath);
            assertFalse(opener.waitFor(200, TimeUnit.MILLISECONDS), "opener bypassed migration lock");
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS), "holder did not halt");
            assertEquals(0, holder.exitValue(), processOutput(holder));
            assertTrue(opener.waitFor(10, TimeUnit.SECONDS), "opener did not recover after halt");
            assertEquals(0, opener.exitValue(), processOutput(opener));
        } finally {
            holder.destroyForcibly();
            if (opener != null) {
                opener.destroyForcibly();
            }
        }
    }

    @Test
    void configureFailureKeepsConnectionCloseFailureSuppressed() throws Exception {
        var databasePath = RuntimePaths.forTest(tempDir).stateDatabase();
        try (var database = StateDatabase.open(databasePath)) {
            var sqliteDriver = DriverManager.drivers()
                    .filter(driver -> driver.getClass().getName().equals("org.sqlite.JDBC"))
                    .findFirst()
                    .orElseThrow();
            var configureFailure = new SQLException("configure failed");
            var closeFailure = new SQLException("close failed");
            var failingDriver = failingDriver(configureFailure, closeFailure);
            DriverManager.deregisterDriver(sqliteDriver);
            DriverManager.registerDriver(failingDriver);
            DriverManager.registerDriver(sqliteDriver);
            try {
                var thrown = assertThrows(SQLException.class, database::openConnection);
                assertSame(configureFailure, thrown);
                assertEquals(1, thrown.getSuppressed().length);
                assertSame(closeFailure, thrown.getSuppressed()[0]);
            } finally {
                DriverManager.deregisterDriver(failingDriver);
                DriverManager.deregisterDriver(sqliteDriver);
                DriverManager.registerDriver(sqliteDriver);
            }
        }
    }

    private static int intPragma(Connection connection, String pragma) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA " + pragma)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void assertSqlRejected(Connection connection, String sql) {
        assertThrows(SQLException.class, () -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        });
    }

    private static String textPragma(Connection connection, String pragma) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA " + pragma)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static int countUserTables(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sqlite_master "
                                + "WHERE type = 'table' AND name NOT LIKE 'sqlite_%'");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void assertColumns(Connection connection, String table, Set<String> expected)
            throws SQLException {
        var actual = new HashSet<String>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                actual.add(rows.getString("name"));
            }
        }
        assertTrue(actual.containsAll(expected), () -> table + " columns: " + actual);
    }

    private static void assertForeignKey(
            Connection connection, String fromTable, String toTable, String fromColumn, String toColumn)
            throws SQLException {
        var found = false;
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA foreign_key_list(" + fromTable + ")")) {
            while (rows.next()) {
                found |= toTable.equals(rows.getString("table"))
                        && fromColumn.equals(rows.getString("from"))
                        && toColumn.equals(rows.getString("to"));
            }
        }
        assertTrue(found, () -> fromTable + "." + fromColumn + " lacks FK to "
                + toTable + "." + toColumn);
    }

    private static void assertNotNullColumns(
            Connection connection, String table, Set<String> expectedNotNull) throws SQLException {
        var actual = new HashSet<String>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (rows.getInt("notnull") != 0 || rows.getInt("pk") != 0) {
                    actual.add(rows.getString("name"));
                }
            }
        }
        assertTrue(actual.containsAll(expectedNotNull), () -> table + " NOT NULL columns: " + actual);
    }

    private static Set<String> indexes(Connection connection, String table) throws SQLException {
        var indexes = new HashSet<String>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (rows.next()) {
                indexes.add(rows.getString("name"));
            }
        }
        return indexes;
    }

    private static Process startProbe(String mode, Path databasePath, Path readyPath)
            throws java.io.IOException {
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) {
            java = Path.of(System.getProperty("java.home"), "bin", "java");
        }
        return new ProcessBuilder(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        StateDatabaseProcessProbe.class.getName(),
                        mode,
                        databasePath.toString(),
                        readyPath.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitFile(Path path) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(path), "holder did not signal readiness");
    }

    private static String processOutput(Process process) throws java.io.IOException {
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Driver failingDriver(SQLException configureFailure, SQLException closeFailure) {
        return new Driver() {
            @Override
            public Connection connect(String url, Properties info) {
                if (!acceptsURL(url)) {
                    return null;
                }
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "createStatement" -> throw configureFailure;
                            case "close" -> throw closeFailure;
                            case "isClosed" -> false;
                            case "unwrap" -> throw new SQLException("not a wrapper");
                            case "isWrapperFor" -> false;
                            case "toString" -> "failing-connection";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
            }

            @Override
            public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:sqlite:");
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                return new DriverPropertyInfo[0];
            }

            @Override
            public int getMajorVersion() {
                return 1;
            }

            @Override
            public int getMinorVersion() {
                return 0;
            }

            @Override
            public boolean jdbcCompliant() {
                return false;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }
        };
    }
}
