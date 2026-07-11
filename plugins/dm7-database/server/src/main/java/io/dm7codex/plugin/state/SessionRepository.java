package io.dm7codex.plugin.state;

import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import io.dm7codex.plugin.sql.ParsedStatement;

public final class SessionRepository {
    private static final int INITIAL_VERSION = 1;
    private static final String UNBOUND = "unbound";

    private final StateDatabase database;
    private final Path sessionsDirectory;

    public SessionRepository(StateDatabase database, Path sessionsDirectory) {
        this.database = Objects.requireNonNull(database, "database");
        this.sessionsDirectory = Objects.requireNonNull(sessionsDirectory, "sessionsDirectory")
                .toAbsolutePath()
                .normalize();
    }

    public SessionState initialize(
            SessionIdentity identity,
            String externalIdHash,
            Path activeSql,
            ActiveSqlCreator activeSqlCreator)
            throws SQLException, IOException {
        var normalizedActiveSql = requireExpectedActiveSql(externalIdHash, activeSql);
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

    public Optional<SessionState> findActive(String sessionId) throws SQLException {
        Objects.requireNonNull(sessionId, "sessionId");
        try (var connection = database.openConnection()) {
            return Optional.ofNullable(findActive(connection, sessionId));
        }
    }

    public SessionState requireActive(SessionState supplied) throws SQLException {
        Objects.requireNonNull(supplied, "supplied");
        requireTrustedSuppliedPath(supplied);
        try (var connection = database.openConnection()) {
            var current = findActive(connection, supplied.sessionId());
            if (current == null || !sameSessionGeneration(supplied, current)) {
                throw new SQLException("Session state is not the active release version");
            }
            return current;
        }
    }

    public SessionState requireCurrentAtLeast(SessionState supplied) throws SQLException {
        Objects.requireNonNull(supplied, "supplied");
        requireTrustedSuppliedPath(supplied);
        try (var connection = database.openConnection()) {
            var current = findActive(connection, supplied.sessionId());
            if (current == null || !sameImmutableSession(supplied, current)
                    || current.version() < supplied.version()) {
                throw new SQLException("Session state is not a trusted release generation");
            }
            return current;
        }
    }

    public PendingReleaseOperation beginPending(
            SessionState supplied,
            String operationId,
            String fingerprint,
            ParsedStatement statement,
            String replayableSql,
            long fileOffset,
            String blockSha256,
            boolean bindingComment)
            throws SQLException {
        requireOperationId(operationId);
        requireFingerprint(fingerprint);
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(replayableSql, "replayableSql");
        try (var connection = database.openConnection()) {
            StateDatabase.execute(connection, "BEGIN IMMEDIATE");
            try {
                var current = findActive(connection, supplied.sessionId());
                if (current == null || !sameSessionGeneration(supplied, current)) {
                    throw new SQLException("Session state is not the active release version");
                }
                var existing = findOperation(connection, operationId);
                if (existing != null) {
                    if (!existing.sessionId().equals(supplied.sessionId())) {
                        throw new SQLException("Release operation identifier is already in use");
                    }
                    StateDatabase.execute(connection, "COMMIT");
                    return existing;
                }
                var sequence = nextSequence(connection, supplied.sessionId());
                try (var insert = connection.prepareStatement("""
                        INSERT INTO statement_event(
                            session_id, release_version, statement_index, sequence_number,
                            statement_kind, status, phase, recorded, raw_sql, replayable_sql,
                            operation_id, pending_fingerprint, file_offset, block_sha256,
                            binding_comment, created_at
                        ) VALUES (?, ?, ?, ?, ?, 'PENDING', 'COMMITTED', 0, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, supplied.sessionId());
                    insert.setInt(2, supplied.version());
                    insert.setInt(3, statement.index());
                    insert.setLong(4, sequence);
                    insert.setString(5, statement.kind().name());
                    insert.setString(6, statement.originalSql());
                    insert.setString(7, replayableSql);
                    insert.setString(8, operationId);
                    insert.setString(9, fingerprint);
                    insert.setLong(10, fileOffset);
                    insert.setString(11, blockSha256);
                    insert.setInt(12, bindingComment ? 1 : 0);
                    insert.setString(13, Instant.now().toString());
                    insert.executeUpdate();
                }
                var pending = findOperation(connection, operationId);
                StateDatabase.execute(connection, "COMMIT");
                return pending;
            } catch (SQLException | RuntimeException failure) {
                StateDatabase.rollback(connection, failure);
                throw failure;
            }
        }
    }

    public Optional<PendingReleaseOperation> findOperation(String operationId)
            throws SQLException {
        requireOperationId(operationId);
        try (var connection = database.openConnection()) {
            return Optional.ofNullable(findOperation(connection, operationId));
        }
    }

    public java.util.List<PendingReleaseOperation> findPending(String sessionId, int version)
            throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        SELECT * FROM statement_event
                        WHERE session_id = ? AND release_version = ?
                          AND operation_id IS NOT NULL AND recorded = 0
                        ORDER BY sequence_number
                        """)) {
            statement.setString(1, sessionId);
            statement.setInt(2, version);
            try (var rows = statement.executeQuery()) {
                var pending = new java.util.ArrayList<PendingReleaseOperation>();
                while (rows.next()) pending.add(readPending(rows));
                return java.util.List.copyOf(pending);
            }
        }
    }

    public void finalizePending(PendingReleaseOperation pending) throws SQLException {
        Objects.requireNonNull(pending, "pending");
        try (var connection = database.openConnection()) {
            StateDatabase.execute(connection, "BEGIN IMMEDIATE");
            try {
                var stored = findOperation(connection, pending.operationId());
                if (stored == null) throw new SQLException("Pending release operation is missing");
                if (stored.recorded()) {
                    StateDatabase.execute(connection, "COMMIT");
                    return;
                }
                var current = findActive(connection, pending.sessionId());
                if (current == null || current.version() != pending.version()) {
                    throw new SQLException("Pending release operation is not on the active version");
                }
                if (!UNBOUND.equals(current.databaseFingerprint())
                        && !current.databaseFingerprint().equals(pending.fingerprint())) {
                    throw new FingerprintMismatchException();
                }
                if (UNBOUND.equals(current.databaseFingerprint())) {
                    try (var bind = connection.prepareStatement("""
                            UPDATE release_version SET database_fingerprint = ?
                            WHERE session_id = ? AND version = ? AND status = 'active'
                              AND database_fingerprint = 'unbound'
                            """)) {
                        bind.setString(1, pending.fingerprint());
                        bind.setString(2, pending.sessionId());
                        bind.setInt(3, pending.version());
                        if (bind.executeUpdate() != 1) {
                            throw new SQLException("Unable to finalize release binding");
                        }
                    }
                }
                try (var updateEvent = connection.prepareStatement("""
                        UPDATE statement_event SET recorded = 1, status = 'SUCCEEDED'
                        WHERE operation_id = ? AND recorded = 0
                        """)) {
                    updateEvent.setString(1, pending.operationId());
                    if (updateEvent.executeUpdate() != 1) {
                        throw new SQLException("Unable to finalize release operation");
                    }
                }
                try (var updateRelease = connection.prepareStatement("""
                        UPDATE release_version
                        SET statement_count = statement_count + 1,
                            first_sequence = COALESCE(first_sequence, ?), last_sequence = ?
                        WHERE session_id = ? AND version = ? AND status = 'active'
                        """)) {
                    updateRelease.setLong(1, pending.sequence());
                    updateRelease.setLong(2, pending.sequence());
                    updateRelease.setString(3, pending.sessionId());
                    updateRelease.setInt(4, pending.version());
                    if (updateRelease.executeUpdate() != 1) {
                        throw new SQLException("Unable to finalize release metadata");
                    }
                }
                StateDatabase.execute(connection, "COMMIT");
            } catch (SQLException | RuntimeException failure) {
                StateDatabase.rollback(connection, failure);
                throw failure;
            }
        }
    }

    public boolean bindOrAssertFingerprint(SessionState supplied, String fingerprint)
            throws SQLException {
        requireFingerprint(fingerprint);
        requireTrustedSuppliedPath(supplied);
        try (var connection = database.openConnection()) {
            StateDatabase.execute(connection, "BEGIN IMMEDIATE");
            try {
                var current = findActive(connection, supplied.sessionId());
                if (current == null || !sameSessionGeneration(supplied, current)) {
                    throw new SQLException("Session state is not the active release version");
                }
                if (!UNBOUND.equals(current.databaseFingerprint())) {
                    if (!current.databaseFingerprint().equals(fingerprint)) {
                        throw new FingerprintMismatchException();
                    }
                    StateDatabase.execute(connection, "COMMIT");
                    return false;
                }
                try (var update = connection.prepareStatement("""
                        UPDATE release_version SET database_fingerprint = ?
                        WHERE session_id = ? AND version = ? AND status = 'active'
                          AND database_fingerprint = 'unbound'
                        """)) {
                    update.setString(1, fingerprint);
                    update.setString(2, supplied.sessionId());
                    update.setInt(3, supplied.version());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Unable to bind active release version");
                    }
                }
                StateDatabase.execute(connection, "COMMIT");
                return true;
            } catch (SQLException | RuntimeException failure) {
                StateDatabase.rollback(connection, failure);
                throw failure;
            }
        }
    }

    public void assertCompatible(SessionState supplied, String fingerprint) throws SQLException {
        requireFingerprint(fingerprint);
        var current = requireActive(supplied);
        if (!UNBOUND.equals(current.databaseFingerprint())
                && !current.databaseFingerprint().equals(fingerprint)) {
            throw new FingerprintMismatchException();
        }
    }

    public long recordReleaseStatement(SessionState supplied, ParsedStatement statement,
                                       String replayableSql) throws SQLException {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(replayableSql, "replayableSql");
        requireTrustedSuppliedPath(supplied);
        try (var connection = database.openConnection()) {
            StateDatabase.execute(connection, "BEGIN IMMEDIATE");
            try {
                var current = findActive(connection, supplied.sessionId());
                if (current == null || !sameSessionGeneration(supplied, current)) {
                    throw new SQLException("Session state is not the active release version");
                }
                long sequence = nextSequence(connection, supplied.sessionId());
                try (var insert = connection.prepareStatement("""
                        INSERT INTO statement_event(
                            session_id, release_version, statement_index, sequence_number,
                            statement_kind, status, phase, recorded, raw_sql, replayable_sql,
                            created_at
                        ) VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', 'COMMITTED', 1, ?, ?, ?)
                        """)) {
                    insert.setString(1, supplied.sessionId());
                    insert.setInt(2, supplied.version());
                    insert.setInt(3, statement.index());
                    insert.setLong(4, sequence);
                    insert.setString(5, statement.kind().name());
                    insert.setString(6, statement.originalSql());
                    insert.setString(7, replayableSql);
                    insert.setString(8, Instant.now().toString());
                    insert.executeUpdate();
                }
                try (var update = connection.prepareStatement("""
                        UPDATE release_version
                        SET statement_count = statement_count + 1,
                            first_sequence = COALESCE(first_sequence, ?), last_sequence = ?
                        WHERE session_id = ? AND version = ? AND status = 'active'
                        """)) {
                    update.setLong(1, sequence);
                    update.setLong(2, sequence);
                    update.setString(3, supplied.sessionId());
                    update.setInt(4, supplied.version());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Unable to update release sequence metadata");
                    }
                }
                StateDatabase.execute(connection, "COMMIT");
                return sequence;
            } catch (SQLException | RuntimeException failure) {
                StateDatabase.rollback(connection, failure);
                throw failure;
            }
        }
    }

    public ReleaseVersion findVersion(String sessionId, int version) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        SELECT session_id, version, database_fingerprint, active_sql, status,
                               statement_count, first_sequence, last_sequence, created_at
                        FROM release_version WHERE session_id = ? AND version = ?
                        """)) {
            statement.setString(1, sessionId);
            statement.setInt(2, version);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new ReleaseVersionNotFoundException();
                try {
                    return readReleaseVersion(rows);
                } catch (java.nio.file.InvalidPathException
                        | java.time.format.DateTimeParseException corrupt) {
                    throw new ReleaseMetadataCorruptException(corrupt);
                }
            }
        }
    }

    public SessionState createNextActiveVersion(
            String sessionId, String externalIdHash, int version, Path activeSql, Instant createdAt)
            throws SQLException {
        var normalized = requireExpectedActiveSql(externalIdHash, activeSql);
        try (var connection = database.openConnection()) {
            StateDatabase.execute(connection, "BEGIN IMMEDIATE");
            try {
                var existing = findActive(connection, sessionId);
                if (existing != null) {
                    if (existing.version() != version || !existing.activeSql().equals(normalized)) {
                        throw new SQLException("A different active release version already exists");
                    }
                    StateDatabase.execute(connection, "COMMIT");
                    return existing;
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO release_version(
                            session_id, version, database_fingerprint, active_sql, status, created_at
                        ) VALUES (?, ?, 'unbound', ?, 'active', ?)
                        """)) {
                    insert.setString(1, sessionId);
                    insert.setInt(2, version);
                    insert.setString(3, normalized.toString());
                    insert.setString(4, createdAt.toString());
                    insert.executeUpdate();
                }
                var created = findActive(connection, sessionId);
                StateDatabase.execute(connection, "COMMIT");
                return created;
            } catch (SQLException | RuntimeException failure) {
                StateDatabase.rollback(connection, failure);
                throw failure;
            }
        }
    }

    private Path requireExpectedActiveSql(String externalIdHash, Path activeSql)
            throws SQLException {
        Objects.requireNonNull(externalIdHash, "externalIdHash");
        var expected = sessionsDirectory.resolve(externalIdHash).resolve("active.sql")
                .toAbsolutePath()
                .normalize();
        var expectedParent = expected.getParent();
        if (expectedParent == null
                || !sessionsDirectory.equals(expectedParent.getParent())
                || !expected.equals(Objects.requireNonNull(activeSql, "activeSql")
                        .toAbsolutePath()
                        .normalize())) {
            throw new SQLException("Active SQL path does not match the trusted session path");
        }
        return expected;
    }

    private void requireTrustedSuppliedPath(SessionState supplied) throws SQLException {
        Objects.requireNonNull(supplied, "supplied");
        requireExpectedActiveSql(supplied.externalIdHash(), supplied.activeSql());
    }

    private static boolean sameSessionGeneration(SessionState supplied, SessionState current) {
        return supplied.sessionId().equals(current.sessionId())
                && supplied.externalIdHash().equals(current.externalIdHash())
                && supplied.version() == current.version()
                && supplied.activeSql().toAbsolutePath().normalize().equals(current.activeSql())
                && supplied.createdAt().equals(current.createdAt());
    }

    private static boolean sameImmutableSession(SessionState supplied, SessionState current) {
        return supplied.sessionId().equals(current.sessionId())
                && supplied.externalIdHash().equals(current.externalIdHash())
                && supplied.activeSql().toAbsolutePath().normalize().equals(current.activeSql())
                && supplied.createdAt().equals(current.createdAt());
    }

    private static SessionState findActive(Connection connection, String sessionId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT s.session_id, s.external_id_hash, r.version,
                       r.database_fingerprint, r.active_sql, s.created_at
                FROM logical_session s JOIN release_version r ON r.session_id = s.session_id
                WHERE s.session_id = ? AND r.status = 'active'
                """)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new SessionState(
                        rows.getString("session_id"), rows.getString("external_id_hash"),
                        rows.getInt("version"), rows.getString("database_fingerprint"),
                        Path.of(rows.getString("active_sql")).toAbsolutePath().normalize(),
                        Instant.parse(rows.getString("created_at")));
            }
        }
    }

    private static long nextSequence(Connection connection, String sessionId) throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM statement_event "
                                + "WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static PendingReleaseOperation findOperation(Connection connection, String operationId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM statement_event WHERE operation_id = ?")) {
            statement.setString(1, operationId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? readPending(rows) : null;
            }
        }
    }

    private static PendingReleaseOperation readPending(ResultSet rows) throws SQLException {
        return new PendingReleaseOperation(
                rows.getString("operation_id"), rows.getString("session_id"),
                rows.getInt("release_version"), rows.getLong("sequence_number"),
                rows.getInt("statement_index"), rows.getString("statement_kind"),
                rows.getString("raw_sql"), rows.getString("replayable_sql"),
                rows.getString("pending_fingerprint"), rows.getLong("file_offset"),
                rows.getString("block_sha256"), rows.getInt("binding_comment") != 0,
                rows.getInt("recorded") != 0);
    }

    private static ReleaseVersion readReleaseVersion(ResultSet rows) throws SQLException {
        var first = rows.getLong("first_sequence");
        Long firstSequence = rows.wasNull() ? null : first;
        var last = rows.getLong("last_sequence");
        Long lastSequence = rows.wasNull() ? null : last;
        return new ReleaseVersion(
                rows.getString("session_id"), rows.getInt("version"),
                rows.getString("database_fingerprint"),
                Path.of(rows.getString("active_sql")).toAbsolutePath().normalize(),
                rows.getString("status"), rows.getInt("statement_count"),
                firstSequence, lastSequence, Instant.parse(rows.getString("created_at")));
    }

    private static void requireFingerprint(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.isBlank() || UNBOUND.equals(fingerprint)
                || !fingerprint.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("A bound database fingerprint is required");
        }
    }

    private static void requireOperationId(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        if (operationId.isBlank() || operationId.length() > 512) {
            throw new IllegalArgumentException("A stable release operation identifier is required");
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

    public record ReleaseVersion(
            String sessionId, int version, String databaseFingerprint, Path activeSql,
            String status, int statementCount, Long firstSequence, Long lastSequence,
            Instant createdAt) {}

    public record PendingReleaseOperation(
            String operationId,
            String sessionId,
            int version,
            long sequence,
            int statementIndex,
            String statementKind,
            String originalSql,
            String replayableSql,
            String fingerprint,
            long fileOffset,
            String blockSha256,
            boolean bindingComment,
            boolean recorded) {}

    public static final class FingerprintMismatchException extends SQLException {
        public FingerprintMismatchException() {
            super("Active release version is bound to another database");
        }
    }
}
