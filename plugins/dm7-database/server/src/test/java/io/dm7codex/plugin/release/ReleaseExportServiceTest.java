package io.dm7codex.plugin.release;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.release.ReleaseExportService.ExportStage;
import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionInitializer;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SqlPurpose;
import io.dm7codex.plugin.state.ExportRepository;
import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.StateDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ReleaseExportServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-11T01:02:03Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void headerOnlyExportSucceedsAndRotatesV001ToV002() throws Exception {
        try (var fixture = fixture("empty")) {
            var artifact = fixture.exports.export(fixture.session);

            assertEquals("v001", artifact.version());
            assertEquals("v002", artifact.newActiveVersion());
            assertEquals(0, artifact.statementCount());
            assertTrue(Files.exists(artifact.path()));
            assertTrue(Files.readString(artifact.path(), UTF_8).contains("database-fingerprint: unbound"));
            var active = fixture.sessions.findActive(fixture.session.sessionId()).orElseThrow();
            assertEquals(2, active.version());
            assertTrue(Files.readString(active.activeSql(), UTF_8).contains("version: v002"));
            assertFalse(Files.readString(active.activeSql(), UTF_8).contains("version: v001"));
        }
    }

    @Test
    void exportHeaderContainsSourceHashAndMetadataStoresDifferentFinalHashAndSequenceRange()
            throws Exception {
        try (var fixture = fixture("hash")) {
            fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    parsed("CREATE TABLE 中文表(ID INT)"), "CREATE TABLE 中文表(ID INT)");
            fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.PRODUCTION_CHANGE,
                    parsed("INSERT INTO 中文表 VALUES (1)"), "INSERT INTO 中文表 VALUES (1)");

            var artifact = fixture.exports.export(fixture.session);
            var bytes = Files.readAllBytes(artifact.path());
            var finalSha = sha256(bytes);
            var text = new String(bytes, UTF_8);
            assertTrue(text.contains("sealed-source-sha256: " + artifact.sealedSourceSha256()));
            assertEquals(finalSha, artifact.sha256());
            assertNotEquals(artifact.sealedSourceSha256(), artifact.sha256());
            assertEquals(1L, artifact.firstSequence());
            assertEquals(2L, artifact.lastSequence());
            assertFalse(text.contains(fixture.paths.pluginData().toString()));
            var stored = fixture.exportRepository
                    .findArtifact(fixture.session.sessionId(), fixture.session.version())
                    .orElseThrow();
            assertEquals(finalSha, stored.artifactSha256());
            assertEquals(artifact.sealedSourceSha256(), fixture.exportRepository
                    .findSealed(fixture.session.sessionId(), fixture.session.version())
                    .orElseThrow().sealedSourceSha256());
        }
    }

    @Test
    void concurrentAppendAndExportHaveAWholeStatementCutoff() throws Exception {
        try (var fixture = fixture("cutoff")) {
            fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    parsed("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
            var sealedMoved = new CountDownLatch(1);
            var appendStarted = new CountDownLatch(1);
            var blockingExport = new ReleaseExportService(
                    fixture.paths, fixture.sessions, fixture.exportRepository,
                    Duration.ofSeconds(2), CLOCK, stage -> {
                        if (stage == ExportStage.AFTER_SEAL_MOVE) {
                            sealedMoved.countDown();
                            try {
                                assertTrue(appendStarted.await(
                                        2, java.util.concurrent.TimeUnit.SECONDS));
                                Thread.sleep(200);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException("test interrupted", interrupted);
                            }
                        }
                    });
            var pool = Executors.newFixedThreadPool(2);
            try {
                var exporting = pool.submit(() -> {
                    return blockingExport.export(fixture.session);
                });
                assertTrue(sealedMoved.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var appending = pool.submit(() -> {
                    appendStarted.countDown();
                    fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                            parsed("ALTER TABLE A ADD C INT"), "ALTER TABLE A ADD C INT");
                    return null;
                });
                var artifact = exporting.get();
                appending.get();
                var sealedText = Files.readString(artifact.path(), UTF_8);
                var active = fixture.sessions.findActive(fixture.session.sessionId()).orElseThrow();
                var activeText = Files.readString(active.activeSql(), UTF_8);
                assertFalse(sealedText.contains("ALTER TABLE A ADD C INT"));
                assertTrue(activeText.contains("ALTER TABLE A ADD C INT"));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void oldSessionStateRebasesToV002AfterExportCompletedBeforeReservation() throws Exception {
        try (var fixture = fixture("old-state-rebase")) {
            fixture.exports.export(fixture.session);

            try (var reservation = fixture.logs.reserveWritable(
                    fixture.session, "db-a", SqlPurpose.MIGRATION)) {
                fixture.logs.recordCommitted(reservation, "old-state-operation",
                        parsed("CREATE TABLE V2_TABLE(ID INT)"),
                        "CREATE TABLE V2_TABLE(ID INT)");
            }

            var active = fixture.sessions.findActive(fixture.session.sessionId()).orElseThrow();
            assertEquals(2, active.version());
            assertTrue(Files.readString(active.activeSql(), UTF_8)
                    .contains("CREATE TABLE V2_TABLE(ID INT);"));
        }
    }

    @Test
    void heldWriteReservationBlocksExportUntilDatabaseOutcomeAndLoggingFinish() throws Exception {
        try (var fixture = fixture("reservation-blocks-export")) {
            var reservation = fixture.logs.reserveWritable(
                    fixture.session, "db-a", SqlPurpose.MIGRATION);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var exporting = pool.submit(() -> fixture.exports.export(fixture.session));
                Thread.sleep(100);
                assertFalse(exporting.isDone());
                fixture.logs.recordCommitted(reservation, "held-operation",
                        parsed("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
                reservation.close();
                var artifact = exporting.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(Files.readString(artifact.path(), UTF_8)
                        .contains("CREATE TABLE A(ID INT);"));
            } finally {
                reservation.close();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void exportReconcilesPartialCommittedJournalBeforeSealing() throws Exception {
        try (var fixture = fixture("export-reconcile-pending")) {
            var failing = new ReleaseLogService(
                    fixture.paths, fixture.sessions, Duration.ofSeconds(2),
                    new io.dm7codex.plugin.sql.SqlSecurityPolicy(), stage -> {
                        if (stage == ReleaseLogService.RecordStage.AFTER_PARTIAL_APPEND) {
                            throw new java.io.IOException("injected");
                        }
                    });
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> failing.recordCommitted(
                            fixture.session, "db-a", SqlPurpose.MIGRATION,
                            "export-pending-operation", parsed("CREATE TABLE A(ID INT)"),
                            "CREATE TABLE A(ID INT)"));

            var artifact = fixture.exports.export(fixture.session);

            assertEquals(1, artifact.statementCount());
            assertTrue(Files.readString(artifact.path(), UTF_8)
                    .contains("CREATE TABLE A(ID INT);"));
        }
    }

    @Test
    void sealedButUnexportedAndEveryInjectedStageRecoverIdempotently() throws Exception {
        for (var stage : ExportStage.values()) {
            try (var fixture = fixture("recover-" + stage.name())) {
                fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                        parsed("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
                var failing = new ReleaseExportService(fixture.paths, fixture.sessions,
                        fixture.exportRepository, Duration.ofSeconds(2), CLOCK,
                        reached -> {
                            if (reached == stage) throw new java.io.IOException("injected");
                        });
                try {
                    failing.export(fixture.session);
                } catch (java.io.IOException expected) {
                    // Recovery below must converge from every durable boundary.
                }

                var recovered = fixture.exports.export(fixture.session);
                var repeated = fixture.exports.export(fixture.session);
                assertEquals(recovered.path(), repeated.path(), stage::name);
                assertEquals(recovered.sha256(), repeated.sha256(), stage::name);
                assertTrue(Files.exists(recovered.path()), stage::name);
                assertEquals(2, fixture.sessions.findActive(fixture.session.sessionId())
                        .orElseThrow().version(), stage::name);
                assertEquals(1, Files.list(recovered.path().getParent())
                        .filter(path -> path.getFileName().toString().endsWith(".sql"))
                        .count(), stage::name);
            }
        }
    }

    @Test
    void finalArtifactHistoryIsImmutableAcrossLaterExports() throws Exception {
        try (var fixture = fixture("history")) {
            fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    parsed("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
            var first = fixture.exports.export(fixture.session);
            var firstBytes = Files.readAllBytes(first.path());
            var current = fixture.sessions.findActive(fixture.session.sessionId()).orElseThrow();
            fixture.logs.recordCommitted(current, "db-b", SqlPurpose.MIGRATION,
                    parsed("CREATE TABLE B(ID INT)"), "CREATE TABLE B(ID INT)");
            var second = fixture.exports.export(current);

            assertNotEquals(first.path(), second.path());
            assertArrayEquals(firstBytes, Files.readAllBytes(first.path()));
        }
    }

    @Test
    void sealedSourceMetadataCannotBeRewrittenAfterDurableSeal() throws Exception {
        try (var fixture = fixture("sealed-immutable")) {
            var artifact = fixture.exports.export(fixture.session);
            var original = fixture.exportRepository
                    .findSealed(fixture.session.sessionId(), fixture.session.version())
                    .orElseThrow();
            var replacement = new ExportRepository.SealedRelease(
                    original.sessionId(), original.version(), original.sealedSourcePath(),
                    "0".repeat(64), original.firstSequence(), original.lastSequence(),
                    original.statementCount(), original.sealedAt());

            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> fixture.exportRepository.recordSealed(replacement));

            assertEquals(original, fixture.exportRepository
                    .findSealed(fixture.session.sessionId(), fixture.session.version())
                    .orElseThrow());
            assertTrue(Files.exists(artifact.path()));
        }
    }

    @Test
    void completeMetadataWithMissingFinalArtifactIsRebuiltFromVerifiedSealedSource()
            throws Exception {
        try (var fixture = fixture("missing-final")) {
            fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    parsed("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
            var first = fixture.exports.export(fixture.session);
            var expected = Files.readAllBytes(first.path());
            Files.delete(first.path());

            var recovered = fixture.exports.export(fixture.session);

            assertEquals(first.path(), recovered.path());
            assertEquals(first.sha256(), recovered.sha256());
            assertArrayEquals(expected, Files.readAllBytes(recovered.path()));
        }
    }

    @Test
    void tamperedSealedAndArtifactPathsAreRejectedWithoutTouchingExternalFiles()
            throws Exception {
        try (var fixture = fixture("tampered-export-path")) {
            fixture.logs.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    parsed("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
            var artifact = fixture.exports.export(fixture.session);
            var outside = tempDir.resolve("external-release.sql").toAbsolutePath();
            Files.writeString(outside, "must survive", UTF_8);
            try (var connection = fixture.database.openConnection();
                    var update = connection.prepareStatement("""
                            UPDATE export_artifact SET artifact_path = ?
                            WHERE session_id = ? AND version = ?
                            """)) {
                update.setString(1, outside.toString());
                update.setString(2, fixture.session.sessionId());
                update.setInt(3, fixture.session.version());
                update.executeUpdate();
            }

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> fixture.exports.export(fixture.session));
            assertEquals("must survive", Files.readString(outside, UTF_8));
            assertTrue(Files.exists(artifact.path()));
        }
    }

    @Test
    void sealedDirectorySymlinkCannotRedirectRotationOutsidePluginData() throws Exception {
        try (var fixture = fixture("sealed-symlink")) {
            var outside = tempDir.resolve("sealed-outside");
            Files.createDirectories(outside);
            var sentinel = outside.resolve("sentinel.txt");
            Files.writeString(sentinel, "must survive", UTF_8);
            var sealed = fixture.session.activeSql().getParent().resolve("sealed");
            assumeSymlink(sealed, outside);

            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> fixture.exports.export(fixture.session));
            assertEquals("must survive", Files.readString(sentinel, UTF_8));
            assertEquals(1, Files.list(outside).count());
        }
    }

    @Test
    void exportDirectoryJunctionCannotRedirectArtifactOutsidePluginData() throws Exception {
        try (var fixture = fixture("export-junction")) {
            var outside = tempDir.resolve("export-outside");
            Files.createDirectories(outside);
            var sentinel = outside.resolve("sentinel.txt");
            Files.writeString(sentinel, "must survive", UTF_8);
            Files.createDirectories(fixture.paths.exportsDirectory());
            var exportDirectory = fixture.paths.exportsDirectory()
                    .resolve(fixture.session.externalIdHash());
            assumeSymlink(exportDirectory, outside);

            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> fixture.exports.export(fixture.session));
            assertEquals("must survive", Files.readString(sentinel, UTF_8));
            try (var files = Files.list(outside)) {
                assertEquals(1, files.count());
            }
        }
    }

    @Test
    void unknownExportTempSymlinkFailsClosedWithoutTouchingTarget() throws Exception {
        try (var fixture = fixture("temp-symlink")) {
            var exportDir = fixture.paths.exportsDirectory()
                    .resolve(fixture.session.externalIdHash());
            Files.createDirectories(exportDir);
            var safeShort = fixture.session.sessionId().replaceAll("[^A-Za-z0-9]", "");
            safeShort = safeShort.substring(0, Math.min(12, safeShort.length()));
            var temporary = exportDir.resolve(".dm7-" + safeShort
                    + "-v001-20260711-010203.sql.tmp");
            var outside = tempDir.resolve("temp-target.sql");
            Files.writeString(outside, "must survive", UTF_8);
            assumeSymlink(temporary, outside);

            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> fixture.exports.export(fixture.session));
            assertEquals("must survive", Files.readString(outside, UTF_8));
        }
    }

    @Test
    void existingNextActiveDatabaseStateRepairsMissingFileAndRejectsTamperedFile()
            throws Exception {
        try (var fixture = fixture("next-active-repair")) {
            var first = fixture.exports.export(fixture.session);
            Files.delete(fixture.session.activeSql());

            var recovered = fixture.exports.export(fixture.session);
            assertEquals(first.path(), recovered.path());
            assertTrue(Files.readString(fixture.session.activeSql(), UTF_8)
                    .contains("version: v002"));

            Files.writeString(fixture.session.activeSql(), "tampered\n", UTF_8);
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> fixture.exports.export(fixture.session));
        }
    }

    @Test
    void existingNextActiveLinkFailsClosedWithoutTouchingExternalTarget() throws Exception {
        try (var fixture = fixture("next-active-link")) {
            fixture.exports.export(fixture.session);
            Files.delete(fixture.session.activeSql());
            var outside = tempDir.resolve("external-active.sql");
            Files.writeString(outside, "must survive", UTF_8);
            assumeSymlink(fixture.session.activeSql(), outside);

            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> fixture.exports.export(fixture.session));
            assertEquals("must survive", Files.readString(outside, UTF_8));
        }
    }

    @Test
    void independentJvmAbnormalExitAfterSealMoveIsRecovered() throws Exception {
        var pluginData = tempDir.resolve("jvm-recovery").toAbsolutePath();
        var externalId = "thread-jvm-recovery";
        var paths = RuntimePaths.forTest(pluginData);
        SessionState session;
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            session = new SessionInitializer(paths, sessions)
                    .initialize(new SessionIdentity(externalId, "test_override", "verified"));
            new ReleaseLogService(paths, sessions, Duration.ofSeconds(2)).recordCommitted(
                    session, "db-a", SqlPurpose.MIGRATION,
                    parsed("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
        }
        var process = startProbe(pluginData, externalId, ExportStage.AFTER_SEAL_MOVE);
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(37, process.exitValue(), processOutput(process));

        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            var recovered = new ReleaseExportService(paths, sessions, new ExportRepository(database),
                    Duration.ofSeconds(2), CLOCK, ignored -> {}).export(session);
            assertTrue(Files.exists(recovered.path()));
            assertEquals(2, sessions.findActive(session.sessionId()).orElseThrow().version());
        }
    }

    public static void main(String[] args) throws Exception {
        var pluginData = Path.of(args[0]);
        var externalId = args[1];
        var crashStage = ExportStage.valueOf(args[2]);
        var paths = RuntimePaths.forTest(pluginData);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            var session = new SessionInitializer(paths, sessions)
                    .initialize(new SessionIdentity(externalId, "test_override", "verified"));
            new ReleaseExportService(paths, sessions, new ExportRepository(database),
                    Duration.ofSeconds(2), CLOCK, stage -> {
                        if (stage == crashStage) Runtime.getRuntime().halt(37);
                    }).export(session);
        }
    }

    private Fixture fixture(String name) throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve(name));
        var database = StateDatabase.open(paths.stateDatabase());
        var sessions = new SessionRepository(database, paths.sessionsDirectory());
        var session = new SessionInitializer(paths, sessions)
                .initialize(new SessionIdentity("thread-" + name, "codex_thread", "verified"));
        var exportRepository = new ExportRepository(database);
        return new Fixture(paths, database, sessions, exportRepository, session,
                new ReleaseLogService(paths, sessions, Duration.ofSeconds(2)),
                new ReleaseExportService(paths, sessions, exportRepository,
                        Duration.ofSeconds(2), CLOCK, ignored -> {}));
    }

    private static io.dm7codex.plugin.sql.ParsedStatement parsed(String sql) {
        return new DmSqlParser().parse(sql).get(0);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Process startProbe(Path pluginData, String externalId, ExportStage stage)
            throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                        ReleaseExportServiceTest.class.getName(), pluginData.toString(), externalId,
                        stage.name())
                .redirectErrorStream(true)
                .start();
    }

    private static void assumeSymlink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException denied) {
            if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                    .contains("windows") && Files.isDirectory(target)) {
                var junction = new ProcessBuilder(
                        "cmd.exe", "/c", "mklink", "/J",
                        link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .start();
                if (junction.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                        && junction.exitValue() == 0 && Files.exists(link)) return;
            }
            if (Files.isRegularFile(target)) {
                try {
                    Files.createLink(link, target);
                    return;
                } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
                    // Fall through to an explicit skip on filesystems without either capability.
                }
            }
            org.junit.jupiter.api.Assumptions.abort(
                    "symbolic links and junctions unavailable: " + denied.getClass());
        }
    }

    private static String processOutput(Process process) throws Exception {
        return new String(process.getInputStream().readAllBytes(), UTF_8);
    }

    private record Fixture(RuntimePaths paths, StateDatabase database, SessionRepository sessions,
                           ExportRepository exportRepository, SessionState session,
                           ReleaseLogService logs, ReleaseExportService exports)
            implements AutoCloseable {
        @Override
        public void close() {
            database.close();
        }
    }
}
