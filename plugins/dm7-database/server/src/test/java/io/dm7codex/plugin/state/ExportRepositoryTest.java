package io.dm7codex.plugin.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            var session = new SessionInitializer(paths, new SessionRepository(database))
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
}
