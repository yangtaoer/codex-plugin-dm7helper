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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseLogServiceTest {
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
    void preservesUtf8HintsAndInternalSemicolonsAndWritesOneTopLevelTerminator() throws Exception {
        try (var fixture = fixture("exact")) {
            var rendered = "/*+ APPEND */ INSERT INTO \"中文表\"(C) VALUES ('甲;乙') ;\r\n";
            fixture.service.recordCommitted(fixture.session, "db-a", SqlPurpose.MIGRATION,
                    statement(rendered), rendered);

            var bytes = Files.readAllBytes(fixture.session.activeSql());
            assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xef
                    && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf);
            var sql = new String(bytes, UTF_8);
            assertTrue(sql.endsWith("/*+ APPEND */ INSERT INTO \"中文表\"(C) VALUES ('甲;乙') ;\n"), sql);
            assertFalse(sql.contains("\r"));
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
            assertThrows(IllegalStateException.class,
                    () -> fixture.service.inspect(fixture.session));
        }
    }

    private Fixture fixture(String name) throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve(name));
        var database = StateDatabase.open(paths.stateDatabase());
        var sessions = new SessionRepository(database, paths.sessionsDirectory());
        var session = new SessionInitializer(paths, sessions)
                .initialize(new SessionIdentity("thread-" + name, "codex_thread", "verified"));
        return new Fixture(database, sessions, session,
                new ReleaseLogService(paths, sessions, Duration.ofSeconds(2)));
    }

    private static io.dm7codex.plugin.sql.ParsedStatement statement(String sql) {
        return new DmSqlParser().parse(sql).get(0);
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private record Fixture(StateDatabase database, SessionRepository sessions, SessionState session,
                           ReleaseLogService service) implements AutoCloseable {
        @Override
        public void close() {
            database.close();
        }
    }
}
