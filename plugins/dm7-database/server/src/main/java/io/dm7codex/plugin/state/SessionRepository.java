package io.dm7codex.plugin.state;

import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public final class SessionRepository {
    private static final int INITIAL_VERSION = 1;
    private static final String UNBOUND = "unbound";

    private final StateDatabase database;

    public SessionRepository(StateDatabase database) {
        this.database = database;
    }

    public SessionState initialize(
            SessionIdentity identity,
            String externalIdHash,
            Path activeSql,
            ActiveSqlCreator activeSqlCreator)
            throws SQLException, IOException {
        var normalizedActiveSql = activeSql.toAbsolutePath().normalize();
        var createdFile = false;
        try (var connection = database.openConnection()) {
            StateDatabase.execute(connection, "BEGIN IMMEDIATE");
            try {
                var existing = findByExternalIdHash(connection, externalIdHash);
                if (existing != null) {
                    StateDatabase.execute(connection, "COMMIT");
                    return existing;
                }

                var sessionId = UUID.randomUUID().toString();
                var createdAt = Instant.now();
                activeSqlCreator.create(normalizedActiveSql);
                createdFile = true;

                insertSession(connection, sessionId, externalIdHash, identity, createdAt);
                insertReleaseVersion(connection, sessionId, normalizedActiveSql, createdAt);
                StateDatabase.execute(connection, "COMMIT");
                return new SessionState(
                        sessionId,
                        externalIdHash,
                        INITIAL_VERSION,
                        UNBOUND,
                        normalizedActiveSql,
                        createdAt);
            } catch (SQLException | IOException | RuntimeException failure) {
                StateDatabase.rollback(connection, failure);
                if (createdFile) {
                    try {
                        Files.deleteIfExists(normalizedActiveSql);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
    }

    private static SessionState findByExternalIdHash(Connection connection, String externalIdHash)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT s.session_id, s.external_id_hash, r.version,
                       r.database_fingerprint, r.active_sql, s.created_at
                FROM logical_session s
                JOIN release_version r ON r.session_id = s.session_id
                WHERE s.external_id_hash = ? AND r.status = 'active'
                """)) {
            statement.setString(1, externalIdHash);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new SessionState(
                        result.getString("session_id"),
                        result.getString("external_id_hash"),
                        result.getInt("version"),
                        result.getString("database_fingerprint"),
                        Path.of(result.getString("active_sql")).toAbsolutePath().normalize(),
                        Instant.parse(result.getString("created_at")));
            }
        }
    }

    private static void insertSession(
            Connection connection,
            String sessionId,
            String externalIdHash,
            SessionIdentity identity,
            Instant createdAt)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO logical_session(
                    session_id, external_id_hash, source, isolation, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, sessionId);
            statement.setString(2, externalIdHash);
            statement.setString(3, identity.source());
            statement.setString(4, identity.isolation());
            statement.setString(5, createdAt.toString());
            statement.executeUpdate();
        }
    }

    private static void insertReleaseVersion(
            Connection connection, String sessionId, Path activeSql, Instant createdAt)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO release_version(
                    session_id, version, database_fingerprint, active_sql, status, created_at
                ) VALUES (?, ?, ?, ?, 'active', ?)
                """)) {
            statement.setString(1, sessionId);
            statement.setInt(2, INITIAL_VERSION);
            statement.setString(3, UNBOUND);
            statement.setString(4, activeSql.toString());
            statement.setString(5, createdAt.toString());
            statement.executeUpdate();
        }
    }

    @FunctionalInterface
    public interface ActiveSqlCreator {
        void create(Path activeSql) throws IOException;
    }
}
