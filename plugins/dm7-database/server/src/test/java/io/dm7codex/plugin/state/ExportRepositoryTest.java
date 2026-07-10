package io.dm7codex.plugin.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionInitializer;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsSeparateSourceAndArtifactHashesWithRecoverableState() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(
                            paths, new SessionRepository(database, paths.sessionsDirectory()))
                    .initialize(new SessionIdentity("export-thread", "codex_thread", "verified"));
            var repository = new ExportRepository(database);
            var sealed = new ExportRepository.SealedRelease(
                    session.sessionId(),
                    session.version(),
                    paths.sessionsDirectory().resolve("sealed/v001.sql"),
                    "source-sha256",
                    11L,
                    15L,
                    5,
                    Instant.parse("2026-07-10T07:00:00Z"));
            repository.recordSealed(sealed);

            var recoverable = new ExportRepository.ExportArtifactRecord(
                    "export-1",
                    session.sessionId(),
                    session.version(),
                    "RECOVERY_REQUIRED",
                    null,
                    null,
                    11L,
                    15L,
                    5,
                    Instant.parse("2026-07-10T07:00:01Z"),
                    null,
                    "interrupted after sealing");
            repository.saveArtifact(recoverable);

            assertEquals(sealed, repository.findSealed(
                            session.sessionId(), session.version())
                    .orElseThrow());
            assertEquals(recoverable, repository.findArtifact(
                            session.sessionId(), session.version())
                    .orElseThrow());
            assertEquals(1, repository.findRecoverable().size());

            var complete = new ExportRepository.ExportArtifactRecord(
                    recoverable.exportId(),
                    recoverable.sessionId(),
                    recoverable.version(),
                    "COMPLETE",
                    paths.exportsDirectory().resolve("v001.sql"),
                    "artifact-sha256",
                    recoverable.firstSequence(),
                    recoverable.lastSequence(),
                    recoverable.statementCount(),
                    recoverable.createdAt(),
                    Instant.parse("2026-07-10T07:00:02Z"),
                    null);
            repository.saveArtifact(complete);

            assertEquals(complete, repository.findArtifact(
                            session.sessionId(), session.version())
                    .orElseThrow());
            assertTrue(repository.findRecoverable().isEmpty());
            assertEquals("source-sha256", repository.findSealed(
                            session.sessionId(), session.version())
                    .orElseThrow()
                    .sealedSourceSha256());
            assertEquals("artifact-sha256", repository.findArtifact(
                            session.sessionId(), session.version())
                    .orElseThrow()
                    .artifactSha256());
        }
    }

    @Test
    void sealedReleaseRequiresSourcePathHashAndTimestamp() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(
                            paths, new SessionRepository(database, paths.sessionsDirectory()))
                    .initialize(new SessionIdentity("sealed-integrity", "codex_thread", "verified"));
            try (var connection = database.openConnection()) {
                assertSealedUpdateRejected(
                        connection,
                        session.sessionId(),
                        session.version(),
                        null,
                        "source-sha",
                        "2026-07-10T07:00:00Z");
                assertSealedUpdateRejected(
                        connection,
                        session.sessionId(),
                        session.version(),
                        "/sealed/v001.sql",
                        null,
                        "2026-07-10T07:00:00Z");
                assertSealedUpdateRejected(
                        connection,
                        session.sessionId(),
                        session.version(),
                        "/sealed/v001.sql",
                        "source-sha",
                        null);
                assertSealedUpdateRejected(
                        connection, session.sessionId(), session.version(), " ", " ", " ");
            }
        }
    }

    @Test
    void completeArtifactRequiresPathHashAndTimestamp() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(
                            paths, new SessionRepository(database, paths.sessionsDirectory()))
                    .initialize(new SessionIdentity("artifact-integrity", "codex_thread", "verified"));
            try (var connection = database.openConnection()) {
                assertCompleteInsertRejected(
                        connection,
                        "missing-path",
                        session.sessionId(),
                        session.version(),
                        null,
                        "artifact-sha",
                        "2026-07-10T07:00:00Z");
                assertCompleteInsertRejected(
                        connection,
                        "missing-hash",
                        session.sessionId(),
                        session.version(),
                        "/exports/v001.sql",
                        null,
                        "2026-07-10T07:00:00Z");
                assertCompleteInsertRejected(
                        connection,
                        "missing-time",
                        session.sessionId(),
                        session.version(),
                        "/exports/v001.sql",
                        "artifact-sha",
                        null);
                assertCompleteInsertRejected(
                        connection,
                        "blank-metadata",
                        session.sessionId(),
                        session.version(),
                        " ",
                        " ",
                        " ");
            }
        }
    }

    private static void assertSealedUpdateRejected(
            java.sql.Connection connection,
            String sessionId,
            int version,
            String sourcePath,
            String sourceSha,
            String sealedAt) {
        assertThrows(java.sql.SQLException.class, () -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE release_version
                    SET status = 'sealed', sealed_source_path = ?,
                        sealed_source_sha256 = ?, sealed_at = ?
                    WHERE session_id = ? AND version = ?
                    """)) {
                statement.setString(1, sourcePath);
                statement.setString(2, sourceSha);
                statement.setString(3, sealedAt);
                statement.setString(4, sessionId);
                statement.setInt(5, version);
                statement.executeUpdate();
            }
        });
    }

    private static void assertCompleteInsertRejected(
            java.sql.Connection connection,
            String exportId,
            String sessionId,
            int version,
            String artifactPath,
            String artifactSha,
            String completedAt) {
        assertThrows(java.sql.SQLException.class, () -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO export_artifact(
                        export_id, session_id, version, state, artifact_path,
                        artifact_sha256, created_at, completed_at
                    ) VALUES (?, ?, ?, 'COMPLETE', ?, ?, '2026-07-10T06:59:00Z', ?)
                    """)) {
                statement.setString(1, exportId);
                statement.setString(2, sessionId);
                statement.setInt(3, version);
                statement.setString(4, artifactPath);
                statement.setString(5, artifactSha);
                statement.setString(6, completedAt);
                statement.executeUpdate();
            }
        });
    }
}
