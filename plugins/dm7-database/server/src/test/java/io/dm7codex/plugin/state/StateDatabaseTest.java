package io.dm7codex.plugin.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.runtime.RuntimePaths;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateDatabaseTest {
    @TempDir
    Path tempDir;

    @Test
    void migrationV1CreatesRequiredSchemaAndConnectionPragmas() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);

        try (var database = StateDatabase.open(paths.stateDatabase());
                var connection = database.openConnection()) {
            assertEquals(1, intPragma(connection, "user_version"));
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
            assertEquals(1, intPragma(connection, "user_version"));
            assertEquals(5, countUserTables(connection));
        }
    }

    private static int intPragma(Connection connection, String pragma) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA " + pragma)) {
            rows.next();
            return rows.getInt(1);
        }
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
}
