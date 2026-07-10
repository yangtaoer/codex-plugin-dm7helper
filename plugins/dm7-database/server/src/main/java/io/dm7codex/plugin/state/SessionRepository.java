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
        var creationAttempted = false;
        try (var connection = database.openConnection()) {
            StateDatabase.execute(connection, "BEGIN IMMEDIATE");
            try {
                var existing = findByExternalIdHash(
                        connection, externalIdHash, normalizedActiveSql);
                if (existing != null) {
                    StateDatabase.execute(connection, "COMMIT");
                    return existing;
                }

                recoverUnreferencedOrphan(connection, normalizedActiveSql);
                var sessionId = UUID.randomUUID().toString();
                var createdAt = Instant.now();
                creationAttempted = true;
                activeSqlCreator.create(normalizedActiveSql);

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
                if (creationAttempted) {
                    cleanupUncommittedFile(connection, normalizedActiveSql, failure);
                }
                throw failure;
            }
        }
    }

    private static SessionState findByExternalIdHash(
            Connection connection, String externalIdHash, Path expectedActiveSql)
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
                var storedActiveSql = Path.of(result.getString("active_sql"))
                        .toAbsolutePath()
                        .normalize();
                if (!storedActiveSql.equals(expectedActiveSql)) {
                    throw new SQLException("Stored active SQL path does not match the session path");
                }
                return new SessionState(
                        result.getString("session_id"),
                        result.getString("external_id_hash"),
                        result.getInt("version"),
                        result.getString("database_fingerprint"),
                        storedActiveSql,
                        Instant.parse(result.getString("created_at")));
            }
        }
    }

    private static void recoverUnreferencedOrphan(Connection connection, Path activeSql)
            throws SQLException, IOException {
        if (!Files.exists(activeSql)) {
            return;
        }
        if (isActiveSqlReferenced(connection, activeSql)) {
            throw new SQLException("Active SQL path is owned by another release version");
        }
        Files.delete(activeSql);
    }

    private static void cleanupUncommittedFile(
            Connection connection, Path activeSql, Throwable failure) {
        try {
            if (!isActiveSqlReferenced(connection, activeSql)) {
                Files.deleteIfExists(activeSql);
            }
        } catch (SQLException | IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static boolean isActiveSqlReferenced(Connection connection, Path activeSql)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT active_sql FROM release_version");
                var result = statement.executeQuery()) {
            while (result.next()) {
                try {
                    var referenced = Path.of(result.getString("active_sql"))
                            .toAbsolutePath()
                            .normalize();
                    if (referenced.equals(activeSql)) {
                        return true;
                    }
                } catch (java.nio.file.InvalidPathException invalidPath) {
                    throw new SQLException("Stored active SQL path is invalid", invalidPath);
                }
            }
            return false;
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
