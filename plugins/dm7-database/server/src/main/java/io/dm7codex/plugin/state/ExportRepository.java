package io.dm7codex.plugin.state;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ExportRepository {
    private final StateDatabase database;

    public ExportRepository(StateDatabase database) {
        this.database = database;
    }

    public void recordSealed(SealedRelease release) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        UPDATE release_version
                        SET status = 'sealed', sealed_source_path = ?, sealed_source_sha256 = ?,
                            first_sequence = ?, last_sequence = ?, statement_count = ?, sealed_at = ?
                        WHERE session_id = ? AND version = ?
                        """)) {
            statement.setString(1, normalizedText(release.sealedSourcePath()));
            statement.setString(2, release.sealedSourceSha256());
            setNullableLong(statement, 3, release.firstSequence());
            setNullableLong(statement, 4, release.lastSequence());
            statement.setInt(5, release.statementCount());
            statement.setString(6, release.sealedAt().toString());
            statement.setString(7, release.sessionId());
            statement.setInt(8, release.version());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Release version does not exist");
            }
        }
    }

    public Optional<SealedRelease> findSealed(String sessionId, int version) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        SELECT session_id, version, sealed_source_path, sealed_source_sha256,
                               first_sequence, last_sequence, statement_count, sealed_at
                        FROM release_version
                        WHERE session_id = ? AND version = ? AND sealed_source_sha256 IS NOT NULL
                        """)) {
            statement.setString(1, sessionId);
            statement.setInt(2, version);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readSealed(rows)) : Optional.empty();
            }
        }
    }

    public void saveArtifact(ExportArtifactRecord artifact) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO export_artifact(
                            export_id, session_id, version, state, artifact_path, artifact_sha256,
                            first_sequence, last_sequence, statement_count, created_at,
                            completed_at, error_message
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(session_id, version) DO UPDATE SET
                            export_id = excluded.export_id,
                            state = excluded.state,
                            artifact_path = excluded.artifact_path,
                            artifact_sha256 = excluded.artifact_sha256,
                            first_sequence = excluded.first_sequence,
                            last_sequence = excluded.last_sequence,
                            statement_count = excluded.statement_count,
                            created_at = excluded.created_at,
                            completed_at = excluded.completed_at,
                            error_message = excluded.error_message
                        """)) {
            bindArtifact(statement, artifact);
            statement.executeUpdate();
        }
    }

    public Optional<ExportArtifactRecord> findArtifact(String sessionId, int version)
            throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        SELECT * FROM export_artifact WHERE session_id = ? AND version = ?
                        """)) {
            statement.setString(1, sessionId);
            statement.setInt(2, version);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readArtifact(rows)) : Optional.empty();
            }
        }
    }

    public List<ExportArtifactRecord> findRecoverable() throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        SELECT * FROM export_artifact
                        WHERE state IN ('SEALED', 'RECOVERY_REQUIRED')
                        ORDER BY created_at
                        """);
                var rows = statement.executeQuery()) {
            var artifacts = new ArrayList<ExportArtifactRecord>();
            while (rows.next()) {
                artifacts.add(readArtifact(rows));
            }
            return List.copyOf(artifacts);
        }
    }

    private static void bindArtifact(
            java.sql.PreparedStatement statement, ExportArtifactRecord artifact)
            throws SQLException {
        statement.setString(1, artifact.exportId());
        statement.setString(2, artifact.sessionId());
        statement.setInt(3, artifact.version());
        statement.setString(4, artifact.state());
        statement.setString(5, normalizedText(artifact.artifactPath()));
        statement.setString(6, artifact.artifactSha256());
        setNullableLong(statement, 7, artifact.firstSequence());
        setNullableLong(statement, 8, artifact.lastSequence());
        statement.setInt(9, artifact.statementCount());
        statement.setString(10, artifact.createdAt().toString());
        statement.setString(11, instantText(artifact.completedAt()));
        statement.setString(12, artifact.errorMessage());
    }

    private static SealedRelease readSealed(ResultSet rows) throws SQLException {
        return new SealedRelease(
                rows.getString("session_id"),
                rows.getInt("version"),
                Path.of(rows.getString("sealed_source_path")).toAbsolutePath().normalize(),
                rows.getString("sealed_source_sha256"),
                nullableLong(rows, "first_sequence"),
                nullableLong(rows, "last_sequence"),
                rows.getInt("statement_count"),
                Instant.parse(rows.getString("sealed_at")));
    }

    private static ExportArtifactRecord readArtifact(ResultSet rows) throws SQLException {
        var artifactPath = rows.getString("artifact_path");
        return new ExportArtifactRecord(
                rows.getString("export_id"),
                rows.getString("session_id"),
                rows.getInt("version"),
                rows.getString("state"),
                artifactPath == null ? null : Path.of(artifactPath).toAbsolutePath().normalize(),
                rows.getString("artifact_sha256"),
                nullableLong(rows, "first_sequence"),
                nullableLong(rows, "last_sequence"),
                rows.getInt("statement_count"),
                Instant.parse(rows.getString("created_at")),
                parseInstant(rows.getString("completed_at")),
                rows.getString("error_message"));
    }

    private static void setNullableLong(
            java.sql.PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        var value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static String normalizedText(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize().toString();
    }

    private static String instantText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant parseInstant(String instant) {
        return instant == null ? null : Instant.parse(instant);
    }

    public record SealedRelease(
            String sessionId,
            int version,
            Path sealedSourcePath,
            String sealedSourceSha256,
            Long firstSequence,
            Long lastSequence,
            int statementCount,
            Instant sealedAt) {}

    public record ExportArtifactRecord(
            String exportId,
            String sessionId,
            int version,
            String state,
            Path artifactPath,
            String artifactSha256,
            Long firstSequence,
            Long lastSequence,
            int statementCount,
            Instant createdAt,
            Instant completedAt,
            String errorMessage) {}
}
