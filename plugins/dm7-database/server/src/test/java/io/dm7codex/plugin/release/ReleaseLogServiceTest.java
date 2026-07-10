package io.dm7codex.plugin.release;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionInitializer;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SecretBearingSqlException;
import io.dm7codex.plugin.sql.SqlPurpose;
import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.StateDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ReleaseLogServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsOnlyEligibleCommittedDdlAndDmlAndDoesNotBindForExcludedCalls() throws Exception {
        try (var fixture = fixture("filter")) {
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.TEST,
                    statement("INSERT INTO A VALUES (1)"), "INSERT INTO A VALUES (1)");
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.PRODUCTION_CHANGE,
                    statement("SELECT 1"), "SELECT 1");
            assertEquals("unbound", fixture.sessions.requireActive(fixture.session).databaseFingerprint());

            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    statement("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");

            var sql = Files.readString(fixture.session.activeSql(), UTF_8);
            assertTrue(sql.contains("CREATE TABLE A(ID INT);\n"));
            assertFalse(sql.contains("INSERT INTO"));
            assertFalse(sql.contains("SELECT 1"));
            assertEquals(1, occurrences(sql, "database-fingerprint: db-a"));
            assertEquals(1, fixture.service.inspect(fixture.session).statementCount());
        }
    }

    @Test
    void recordsSameSqlTwiceWithoutDeduplication() throws Exception {
        try (var fixture = fixture("duplicate")) {
            var parsed = statement("UPDATE A SET NAME='同一条'");
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.PRODUCTION_CHANGE,
                    parsed, "UPDATE A SET NAME='同一条'");
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.PRODUCTION_CHANGE,
                    parsed, "UPDATE A SET NAME='同一条'");

            assertEquals(2, occurrences(Files.readString(fixture.session.activeSql(), UTF_8),
                    "UPDATE A SET NAME='同一条';\n"));
            assertEquals(2, fixture.service.inspect(fixture.session).statementCount());
        }
    }

    @Test
    void reservationHoldsPreflightLockUntilSuccessfulLoggingAndRejectsCrossDatabaseBeforeMutation()
            throws Exception {
        try (var fixture = fixture("reservation-cross-db")) {
            var first = fixture.service.reserveWritable(
                    fixture.session, "db-a", SqlPurpose.MIGRATION);
            var attempted = new CountDownLatch(1);
            var mutations = new AtomicInteger();
            var pool = Executors.newSingleThreadExecutor();
            try {
                var loser = pool.submit(() -> {
                    attempted.countDown();
                    try (var ignored = fixture.service.reserveWritable(
                            fixture.session, "db-b", SqlPurpose.MIGRATION)) {
                        mutations.incrementAndGet();
                    }
                    return null;
                });
                assertTrue(attempted.await(1, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertFalse(loser.isDone(), "preflight lock was released before commit/logging");
                assertEquals(0, mutations.get());

                fixture.service.recordCommitted(first, "operation-a", statement(
                        "CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
                first.close();
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> loser.get(2, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof ReleaseLogConnectionMismatch);
                assertEquals(0, mutations.get());
            } finally {
                first.close();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void abandonedFirstReservationDoesNotBindAndAnotherDatabaseCanProceed() throws Exception {
        try (var fixture = fixture("reservation-abandon")) {
            try (var ignored = fixture.service.reserveWritable(
                    fixture.session, "db-a", SqlPurpose.MIGRATION)) {
                // Simulated database rollback: no committed release record.
            }
            assertEquals("unbound", fixture.sessions.findActive(
                    fixture.session.sessionId()).orElseThrow().databaseFingerprint());

            try (var second = fixture.service.reserveWritable(
                    fixture.session, "db-b", SqlPurpose.MIGRATION)) {
                fixture.service.recordCommitted(second, "operation-b", statement(
                        "CREATE TABLE B(ID INT)"), "CREATE TABLE B(ID INT)");
            }
            assertEquals("db-b", fixture.sessions.findActive(
                    fixture.session.sessionId()).orElseThrow().databaseFingerprint());
        }
    }

    @Test
    void operationIdMakesRetryIdempotentButDifferentOperationsKeepDuplicateSql() throws Exception {
        try (var fixture = fixture("operation-id")) {
            var sql = "UPDATE A SET C=1";
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    "same-operation", statement(sql), sql);
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    "same-operation", statement(sql), sql);
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    "different-operation", statement(sql), sql);

            assertEquals(2, fixture.service.inspect(fixture.session).statementCount());
            assertEquals(2, occurrences(Files.readString(fixture.session.activeSql(), UTF_8),
                    "UPDATE A SET C=1;\n"));
        }
    }

    @Test
    void pendingAndPartialAppendFaultsAreReconciledWithoutLossOrDuplication() throws Exception {
        for (var stage : java.util.List.of(
                ReleaseLogService.RecordStage.BEFORE_PENDING,
                ReleaseLogService.RecordStage.AFTER_PENDING,
                ReleaseLogService.RecordStage.AFTER_PARTIAL_APPEND,
                ReleaseLogService.RecordStage.AFTER_APPEND_FORCE,
                ReleaseLogService.RecordStage.BEFORE_FINALIZE,
                ReleaseLogService.RecordStage.AFTER_FINALIZE)) {
            try (var fixture = fixture("journal-" + stage)) {
                var failing = new ReleaseLogService(
                        fixture.paths, fixture.sessions, Duration.ofSeconds(2),
                        new io.dm7codex.plugin.sql.SqlSecurityPolicy(), reached -> {
                            if (reached == stage) throw new java.io.IOException("injected");
                        });
                assertThrows(java.io.IOException.class, () -> failing.recordCommitted(
                        fixture.session, "db-a", SqlPurpose.MIGRATION, "stable-operation",
                        statement("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)"));

                fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                        "stable-operation", statement("CREATE TABLE A(ID INT)"),
                        "CREATE TABLE A(ID INT)");

                assertEquals(1, fixture.service.inspect(fixture.session).statementCount(), stage::name);
                assertEquals(1, occurrences(Files.readString(fixture.session.activeSql(), UTF_8),
                        "CREATE TABLE A(ID INT);\n"), stage::name);
            }
        }
    }

    @Test
    void independentJvmHaltDuringPartialAppendRecoversStableOperation() throws Exception {
        var pluginData = tempDir.resolve("journal-jvm").toAbsolutePath();
        var paths = RuntimePaths.forTest(pluginData);
        var externalId = "thread-journal-jvm";
        SessionState session;
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            session = new SessionInitializer(paths, sessions)
                    .initialize(new SessionIdentity(externalId, "test_override", "verified"));
        }
        var process = startProbe(
                pluginData, externalId, ReleaseLogService.RecordStage.AFTER_PARTIAL_APPEND);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(37, process.exitValue(), processOutput(process));

        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            var service = new ReleaseLogService(paths, sessions, Duration.ofSeconds(2));
            service.recordCommitted(session, "db-a", SqlPurpose.MIGRATION,
                    "jvm-stable-operation", statement("CREATE TABLE A(ID INT)"),
                    "CREATE TABLE A(ID INT)");
            assertEquals(1, service.inspect(session).statementCount());
            assertEquals(1, occurrences(Files.readString(session.activeSql(), UTF_8),
                    "CREATE TABLE A(ID INT);\n"));
        }
    }

    @Test
    void unexpectedBytesAfterPendingOffsetFailClosedWithoutTruncationOrBinding() throws Exception {
        try (var fixture = fixture("journal-unexpected")) {
            var failing = new ReleaseLogService(
                    fixture.paths, fixture.sessions, Duration.ofSeconds(2),
                    new io.dm7codex.plugin.sql.SqlSecurityPolicy(), stage -> {
                        if (stage == ReleaseLogService.RecordStage.AFTER_PENDING) {
                            throw new java.io.IOException("injected");
                        }
                    });
            assertThrows(java.io.IOException.class, () -> failing.recordCommitted(
                    fixture.session, "db-a", SqlPurpose.MIGRATION, "unexpected-operation",
                    statement("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)"));
            Files.writeString(fixture.session.activeSql(), "unexpected-bytes", UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            var before = Files.readAllBytes(fixture.session.activeSql());

            assertThrows(java.io.IOException.class, () -> fixture.service.recordCommitted(
                    fixture.session, "db-a", SqlPurpose.MIGRATION, "unexpected-operation",
                    statement("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)"));

            assertArrayEquals(before, Files.readAllBytes(fixture.session.activeSql()));
            assertEquals("unbound", fixture.sessions.findActive(fixture.session.sessionId())
                    .orElseThrow().databaseFingerprint());
        }
    }

    public static void main(String[] args) throws Exception {
        var paths = RuntimePaths.forTest(Path.of(args[0]));
        var stage = ReleaseLogService.RecordStage.valueOf(args[2]);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            var session = new SessionInitializer(paths, sessions)
                    .initialize(new SessionIdentity(args[1], "test_override", "verified"));
            var service = new ReleaseLogService(
                    paths, sessions, Duration.ofSeconds(2),
                    new io.dm7codex.plugin.sql.SqlSecurityPolicy(), reached -> {
                        if (reached == stage) Runtime.getRuntime().halt(37);
                    });
            service.recordCommitted(session, "db-a", SqlPurpose.MIGRATION,
                    "jvm-stable-operation", statement("CREATE TABLE A(ID INT)"),
                    "CREATE TABLE A(ID INT)");
        }
    }

    @Test
    void preservesUtf8HintsAndInternalSemicolonsAndWritesOneTopLevelTerminator() throws Exception {
        try (var fixture = fixture("exact")) {
            var rendered = "/*+ APPEND */ INSERT INTO \"中文表\"(C) VALUES ('甲;乙') ;\r\n";
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    statement(rendered), rendered);

            var bytes = Files.readAllBytes(fixture.session.activeSql());
            assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xef
                    && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf);
            var sql = new String(bytes, UTF_8);
            assertTrue(sql.contains("/*+ APPEND */ INSERT INTO \"中文表\"(C) VALUES ('甲;乙') ;\n"), sql);
            assertFalse(sql.contains("\r"));
        }
    }

    @Test
    void placesExactlyOneTopLevelTerminatorBeforeTrailingLineAndBlockComments()
            throws Exception {
        try (var fixture = fixture("trailing-comments")) {
            var line = "/*+ INDEX(A IDX_A) */ UPDATE A SET C='甲;乙'; -- 尾部注释\r\n";
            var block = "UPDATE A SET C=2 /* 尾部;块注释 */  \r\n";
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    "line-comment-op", statement(line), line);
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    "block-comment-op", statement(block), block);

            var sql = Files.readString(fixture.session.activeSql(), UTF_8);
            assertTrue(sql.contains(
                    "/*+ INDEX(A IDX_A) */ UPDATE A SET C='甲;乙'; -- 尾部注释\n"), sql);
            assertTrue(sql.contains("UPDATE A SET C=2; /* 尾部;块注释 */\n"), sql);
            assertFalse(sql.contains("尾部注释\n;"), sql);
            assertFalse(sql.contains("块注释 */  ;"), sql);
        }
    }

    @Test
    void firstEligibleStatementBindsOnceAndCrossDatabaseWriteFailsBeforeChanges() throws Exception {
        try (var fixture = fixture("binding")) {
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    statement("CREATE TABLE A(ID INT)"), "CREATE TABLE A(ID INT)");
            var before = Files.readAllBytes(fixture.session.activeSql());

            var thrown = assertThrows(ReleaseLogConnectionMismatch.class,
                    () -> fixture.service.recordCommitted(fixture.session, "db-b",
                            SqlPurpose.MIGRATION, statement("DROP TABLE A"), "DROP TABLE A"));

            assertArrayEquals(before, Files.readAllBytes(fixture.session.activeSql()));
            assertEquals(1, fixture.service.inspect(fixture.session).statementCount());
            assertFalse(thrown.getMessage().contains("db-a"));
            assertFalse(thrown.getMessage().contains("db-b"));
            assertFalse(thrown.getMessage().contains(fixture.session.activeSql().toString()));
        }
    }

    @Test
    void rejectedSecretBearingSqlDoesNotBindOrAppend() throws Exception {
        try (var fixture = fixture("secret")) {
            var before = Files.readAllBytes(fixture.session.activeSql());
            var sql = "CREATE USER APP IDENTIFIED BY hidden_password";

            assertThrows(SecretBearingSqlException.class,
                    () -> fixture.service.recordCommitted(fixture.session, "db-a",
                            SqlPurpose.PRODUCTION_CHANGE, statement(sql), sql));

            assertArrayEquals(before, Files.readAllBytes(fixture.session.activeSql()));
            assertEquals("unbound", fixture.sessions.requireActive(fixture.session).databaseFingerprint());
        }
    }

    @Test
    void staleSessionStateAndTamperedPathsAreRejectedWithoutTouchingFiles() throws Exception {
        try (var fixture = fixture("stale")) {
            var external = tempDir.resolve("outside.sql").toAbsolutePath();
            Files.writeString(external, "must survive", UTF_8);
            var tampered = new SessionState(fixture.session.sessionId(), fixture.session.externalIdHash(),
                    fixture.session.version(), fixture.session.databaseFingerprint(), external,
                    fixture.session.createdAt());

            assertThrows(IllegalStateException.class,
                    () -> fixture.service.recordCommitted(tampered, "db-a", SqlPurpose.MIGRATION,
                            statement("CREATE TABLE X(ID INT)"), "CREATE TABLE X(ID INT)"));
            assertEquals("must survive", Files.readString(external, UTF_8));

            try (var connection = fixture.database.openConnection();
                    var update = connection.prepareStatement(
                            "UPDATE release_version SET version = 2 WHERE session_id = ?")) {
                update.setString(1, fixture.session.sessionId());
                update.executeUpdate();
            }
            assertThrows(java.io.IOException.class,
                    () -> fixture.service.inspect(fixture.session));
        }
    }

    private Fixture fixture(String name) throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve(name));
        var database = StateDatabase.open(paths.stateDatabase());
        var sessions = new SessionRepository(database, paths.sessionsDirectory());
        var session = new SessionInitializer(paths, sessions)
                .initialize(new SessionIdentity("thread-" + name, "codex_thread", "verified"));
        return new Fixture(paths, database, sessions, session,
                new ReleaseLogService(paths, sessions, Duration.ofSeconds(2)));
    }

    private static io.dm7codex.plugin.sql.ParsedStatement statement(String sql) {
        return new DmSqlParser().parse(sql).get(0);
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private static Process startProbe(
            Path pluginData, String externalId, ReleaseLogService.RecordStage stage)
            throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                        ReleaseLogServiceTest.class.getName(), pluginData.toString(), externalId,
                        stage.name())
                .redirectErrorStream(true)
                .start();
    }

    private static String processOutput(Process process) throws Exception {
        return new String(process.getInputStream().readAllBytes(), UTF_8);
    }

    private record Fixture(RuntimePaths paths, StateDatabase database, SessionRepository sessions,
                           SessionState session, ReleaseLogService service) implements AutoCloseable {
        @Override
        public void close() {
            database.close();
        }
    }
}
