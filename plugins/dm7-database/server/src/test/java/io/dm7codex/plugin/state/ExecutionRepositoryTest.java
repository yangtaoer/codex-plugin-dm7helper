package io.dm7codex.plugin.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            var session = new SessionInitializer(paths, new SessionRepository(database))
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
}
