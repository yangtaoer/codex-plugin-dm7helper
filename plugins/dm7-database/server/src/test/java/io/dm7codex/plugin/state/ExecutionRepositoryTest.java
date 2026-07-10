package io.dm7codex.plugin.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionInitializer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsExecutionAndOrderedStatementHistory() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(
                            paths, new SessionRepository(database, paths.sessionsDirectory()))
                    .initialize(new SessionIdentity("execution-thread", "codex_thread", "verified"));
            var repository = new ExecutionRepository(database);
            var startedAt = Instant.parse("2026-07-10T06:00:00Z");
            var execution = new ExecutionRepository.ExecutionRecord(
                    "execution-1",
                    "correlation-1",
                    session.sessionId(),
                    "connection-fingerprint-1",
                    "MCP",
                    "migration",
                    "更新 T 设置 名称='达梦'",
                    "EXECUTING",
                    "RUNNING",
                    startedAt,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    "not committed");
            repository.saveExecution(execution);

            var event = new ExecutionRepository.StatementEventRecord(
                    execution.executionId(),
                    session.sessionId(),
                    session.version(),
                    0,
                    7L,
                    "DML",
                    "SUCCEEDED",
                    "LOGGING",
                    3L,
                    null,
                    null,
                    true,
                    null,
                    "更新 T 设置 名称='达梦'",
                    "UPDATE T SET NAME='达梦'",
                    Instant.parse("2026-07-10T06:00:01Z"));
            repository.appendStatement(event);

            assertEquals(execution, repository.findExecution(execution.executionId()).orElseThrow());
            assertEquals(List.of(event), repository.findStatements(execution.executionId()));
        }
    }

    @Test
    void statementCannotCombineExecutionAndReleaseFromDifferentSessions() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(
                    paths, new SessionRepository(database, paths.sessionsDirectory()));
            var sessionA = initializer.initialize(
                    new SessionIdentity("execution-session-a", "codex_thread", "verified"));
            var sessionB = initializer.initialize(
                    new SessionIdentity("execution-session-b", "codex_thread", "verified"));
            var repository = new ExecutionRepository(database);
            repository.saveExecution(execution("execution-cross-session", sessionA.sessionId()));

            var mismatched = statement(
                    "execution-cross-session", sessionB.sessionId(), sessionB.version());

            assertThrows(java.sql.SQLException.class, () -> repository.appendStatement(mismatched));
        }
    }

    @Test
    void deletingExecutionDetachesOnlyNullableExecutionId() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(
                            paths, new SessionRepository(database, paths.sessionsDirectory()))
                    .initialize(new SessionIdentity(
                            "execution-delete-thread", "codex_thread", "verified"));
            var repository = new ExecutionRepository(database);
            var execution = execution("execution-delete", session.sessionId());
            repository.saveExecution(execution);
            repository.appendStatement(statement(
                    execution.executionId(), session.sessionId(), session.version()));

            try (var connection = database.openConnection();
                    var delete = connection.prepareStatement(
                            "DELETE FROM execution WHERE execution_id = ?")) {
                delete.setString(1, execution.executionId());
                assertEquals(1, delete.executeUpdate());
            }

            try (var connection = database.openConnection();
                    var query = connection.prepareStatement(
                            "SELECT execution_id, session_id FROM statement_event")) {
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertNull(rows.getString("execution_id"));
                    assertEquals(session.sessionId(), rows.getString("session_id"));
                }
            }
        }
    }

    private static ExecutionRepository.ExecutionRecord execution(
            String executionId, String sessionId) {
        return new ExecutionRepository.ExecutionRecord(
                executionId,
                "correlation-" + executionId,
                sessionId,
                "connection-fingerprint",
                "MCP",
                null,
                "SELECT 1",
                "EXECUTING",
                "RUNNING",
                Instant.parse("2026-07-10T06:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "not committed");
    }

    private static ExecutionRepository.StatementEventRecord statement(
            String executionId, String sessionId, int releaseVersion) {
        return new ExecutionRepository.StatementEventRecord(
                executionId,
                sessionId,
                releaseVersion,
                0,
                1L,
                "QUERY",
                "SUCCEEDED",
                "LOGGING",
                1L,
                null,
                null,
                true,
                null,
                "SELECT 1",
                "SELECT 1",
                Instant.parse("2026-07-10T06:00:01Z"));
    }
}
