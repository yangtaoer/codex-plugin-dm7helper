package io.dm7codex.plugin.state;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class StateDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 2;
    private static final ConcurrentMap<Path, Object> MIGRATION_MONITORS = new ConcurrentHashMap<>();

    private final Path databasePath;

    private StateDatabase(Path databasePath) {
        this.databasePath = databasePath;
    }

    public static StateDatabase open(Path databasePath) throws SQLException, IOException {
        var normalizedPath = databasePath.toAbsolutePath().normalize();
        var parent = normalizedPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("State database must have a parent directory");
        }
        Files.createDirectories(parent);
        var database = new StateDatabase(normalizedPath);
        database.migrateWithLock();
        return database;
    }

    public Connection openConnection() throws SQLException {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try {
            configure(connection);
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        // Connections are short-lived and owned by callers.
    }

    private void migrateWithLock() throws SQLException, IOException {
        var monitor = MIGRATION_MONITORS.computeIfAbsent(databasePath, ignored -> new Object());
        synchronized (monitor) {
            var lockPath = databasePath.resolveSibling(databasePath.getFileName() + ".migration.lock");
            try (var channel = FileChannel.open(
                            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
                migrate();
            }
        }
    }

    private void migrate() throws SQLException {
        try (var connection = openConnection()) {
            execute(connection, "BEGIN IMMEDIATE");
            try {
                var currentVersion = pragmaInt(connection, "user_version");
                if (currentVersion > SCHEMA_VERSION) {
                    throw new SQLException("Unsupported state schema version: " + currentVersion);
                }
                if (currentVersion == 0) {
                    migrateToVersion1(connection);
                    execute(connection, "PRAGMA user_version = 1");
                    currentVersion = 1;
                }
                if (currentVersion == 1) {
                    migrateToVersion2(connection);
                    execute(connection, "PRAGMA user_version = 2");
                }
                execute(connection, "COMMIT");
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private static void configure(Connection connection) throws SQLException {
        execute(connection, "PRAGMA busy_timeout = 5000");
        execute(connection, "PRAGMA foreign_keys = ON");
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("Unable to enable SQLite WAL mode");
            }
        }
    }

    private static void migrateToVersion1(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE logical_session (
                    session_id TEXT PRIMARY KEY,
                    external_id_hash TEXT NOT NULL UNIQUE,
                    source TEXT NOT NULL,
                    isolation TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE release_version (
                    session_id TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK (version > 0),
                    database_fingerprint TEXT NOT NULL,
                    active_sql TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    statement_count INTEGER NOT NULL DEFAULT 0 CHECK (statement_count >= 0),
                    first_sequence INTEGER,
                    last_sequence INTEGER,
                    sealed_source_path TEXT,
                    sealed_source_sha256 TEXT,
                    created_at TEXT NOT NULL,
                    sealed_at TEXT,
                    PRIMARY KEY (session_id, version),
                    FOREIGN KEY (session_id) REFERENCES logical_session(session_id) ON DELETE CASCADE,
                    CHECK (
                        (first_sequence IS NULL AND last_sequence IS NULL)
                        OR (
                            first_sequence IS NOT NULL AND last_sequence IS NOT NULL
                            AND first_sequence >= 0 AND last_sequence >= first_sequence
                        )
                    ),
                    CHECK (
                        status <> 'sealed'
                        OR (
                            sealed_source_path IS NOT NULL
                            AND length(trim(sealed_source_path)) > 0
                            AND sealed_source_sha256 IS NOT NULL
                            AND length(trim(sealed_source_sha256)) > 0
                            AND sealed_at IS NOT NULL
                            AND length(trim(sealed_at)) > 0
                        )
                    )
                )
                """);
        execute(connection, """
                CREATE TABLE execution (
                    execution_id TEXT PRIMARY KEY,
                    correlation_id TEXT NOT NULL UNIQUE,
                    session_id TEXT NOT NULL,
                    connection_fingerprint TEXT NOT NULL,
                    source TEXT NOT NULL,
                    purpose TEXT,
                    sql_text TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    completed_at TEXT,
                    affected_row_count INTEGER CHECK (
                        affected_row_count IS NULL OR affected_row_count >= 0
                    ),
                    returned_row_count INTEGER CHECK (
                        returned_row_count IS NULL OR returned_row_count >= 0
                    ),
                    sql_state TEXT,
                    error_code INTEGER,
                    error_message TEXT,
                    recorded INTEGER NOT NULL DEFAULT 0 CHECK (recorded IN (0, 1)),
                    exclusion_reason TEXT,
                    FOREIGN KEY (session_id) REFERENCES logical_session(session_id) ON DELETE CASCADE,
                    UNIQUE (execution_id, session_id)
                )
                """);
        execute(connection, """
                CREATE TABLE statement_event (
                    event_id INTEGER PRIMARY KEY,
                    execution_id TEXT,
                    session_id TEXT NOT NULL,
                    release_version INTEGER NOT NULL,
                    statement_index INTEGER NOT NULL CHECK (statement_index >= 0),
                    sequence_number INTEGER CHECK (sequence_number IS NULL OR sequence_number >= 0),
                    statement_kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    row_count INTEGER CHECK (row_count IS NULL OR row_count >= 0),
                    sql_state TEXT,
                    error_code INTEGER,
                    recorded INTEGER NOT NULL DEFAULT 0 CHECK (recorded IN (0, 1)),
                    exclusion_reason TEXT,
                    raw_sql TEXT NOT NULL,
                    replayable_sql TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (execution_id, session_id)
                        REFERENCES execution(execution_id, session_id),
                    FOREIGN KEY (session_id, release_version)
                        REFERENCES release_version(session_id, version) ON DELETE CASCADE,
                    UNIQUE (session_id, release_version, sequence_number)
                )
                """);
        execute(connection, """
                CREATE TABLE export_artifact (
                    export_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    artifact_path TEXT,
                    artifact_sha256 TEXT,
                    first_sequence INTEGER,
                    last_sequence INTEGER,
                    statement_count INTEGER NOT NULL DEFAULT 0 CHECK (statement_count >= 0),
                    created_at TEXT NOT NULL,
                    completed_at TEXT,
                    error_message TEXT,
                    FOREIGN KEY (session_id, version)
                        REFERENCES release_version(session_id, version) ON DELETE CASCADE,
                    UNIQUE (session_id, version),
                    CHECK (
                        (first_sequence IS NULL AND last_sequence IS NULL)
                        OR (
                            first_sequence IS NOT NULL AND last_sequence IS NOT NULL
                            AND first_sequence >= 0 AND last_sequence >= first_sequence
                        )
                    ),
                    CHECK (
                        state <> 'COMPLETE'
                        OR (
                            artifact_path IS NOT NULL
                            AND length(trim(artifact_path)) > 0
                            AND artifact_sha256 IS NOT NULL
                            AND length(trim(artifact_sha256)) > 0
                            AND completed_at IS NOT NULL
                            AND length(trim(completed_at)) > 0
                        )
                    )
                )
                """);
        execute(connection, """
                CREATE TRIGGER detach_statement_events_before_execution_delete
                BEFORE DELETE ON execution
                FOR EACH ROW
                BEGIN
                    UPDATE statement_event
                    SET execution_id = NULL
                    WHERE execution_id = OLD.execution_id
                      AND session_id = OLD.session_id;
                END
                """);
        execute(connection, """
                CREATE UNIQUE INDEX one_active_release_per_session
                ON release_version(session_id) WHERE status = 'active'
                """);
        execute(connection, """
                CREATE INDEX execution_by_session_started
                ON execution(session_id, started_at DESC)
                """);
        execute(connection, """
                CREATE INDEX statement_event_by_execution
                ON statement_event(execution_id, statement_index)
                """);
        execute(connection, """
                CREATE UNIQUE INDEX statement_event_execution_index
                ON statement_event(execution_id, statement_index)
                WHERE execution_id IS NOT NULL
                """);
        execute(connection, """
                CREATE INDEX statement_event_by_release_sequence
                ON statement_event(session_id, release_version, sequence_number)
                """);
        execute(connection, """
                CREATE INDEX export_artifact_by_state
                ON export_artifact(state, created_at)
                """);
    }

    private static void migrateToVersion2(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE statement_event ADD COLUMN operation_id TEXT");
        execute(connection, "ALTER TABLE statement_event ADD COLUMN pending_fingerprint TEXT");
        execute(connection, """
                ALTER TABLE statement_event ADD COLUMN file_offset INTEGER
                CHECK (file_offset IS NULL OR file_offset >= 0)
                """);
        execute(connection, "ALTER TABLE statement_event ADD COLUMN block_sha256 TEXT");
        execute(connection, """
                ALTER TABLE statement_event ADD COLUMN binding_comment INTEGER
                CHECK (binding_comment IS NULL OR binding_comment IN (0, 1))
                """);
        execute(connection, """
                CREATE UNIQUE INDEX statement_event_operation_id
                ON statement_event(operation_id) WHERE operation_id IS NOT NULL
                """);
    }

    private static int pragmaInt(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA " + name)) {
            if (!result.next()) {
                throw new SQLException("Missing PRAGMA result: " + name);
            }
            return result.getInt(1);
        }
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static void rollback(Connection connection, Throwable failure) {
        try {
            execute(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
