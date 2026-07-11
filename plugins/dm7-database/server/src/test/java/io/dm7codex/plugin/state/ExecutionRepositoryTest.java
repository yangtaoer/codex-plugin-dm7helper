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
import java.util.UUID;
import io.dm7codex.plugin.execution.ExecutionModels.*;
import io.dm7codex.plugin.sql.SqlKind;
import io.dm7codex.plugin.sql.SqlPurpose;
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

    @Test void advancedHistoryFiltersRemainSessionScopedAndComposable() throws Exception {
        var paths=RuntimePaths.forTest(tempDir);
        try(var database=StateDatabase.open(paths.stateDatabase())){
            var session=new SessionInitializer(paths,new SessionRepository(database,paths.sessionsDirectory()))
                    .initialize(new SessionIdentity("history-filter","test","verified"));
            var repository=new ExecutionRepository(database);UUID executionId=UUID.randomUUID(),correlationId=UUID.randomUUID();
            repository.saveExecution(new ExecutionRepository.ExecutionRecord(executionId.toString(),correlationId.toString(),
                    session.sessionId(),"fingerprint",ExecutionSource.CONSOLE.name(),"MIGRATION","UPDATE T SET C=1",
                    ExecutionStatus.COMPLETED.name(),ExecutionStatus.COMPLETED.name(),Instant.parse("2026-07-10T06:00:00Z"),
                    Instant.parse("2026-07-10T06:00:01Z"),1L,0L,null,null,null,true,null));
            repository.appendStatement(new ExecutionRepository.StatementEventRecord(executionId.toString(),session.sessionId(),
                    session.version(),0,1L,SqlKind.DML.name(),"SUCCEEDED","LOGGING",1L,null,null,true,null,
                    "UPDATE T SET C=1","UPDATE T SET C=1",Instant.parse("2026-07-10T06:00:01Z")));
            var filter=new ExecutionFilter(session.sessionId(),null,ExecutionSource.CONSOLE,null,null,null,true,
                    correlationId,true,SqlKind.DML);
            assertEquals(1,repository.search(filter,0,20).items().size());
            assertEquals(0,repository.search(new ExecutionFilter("other",null,null,null,null,null,true,
                    correlationId,true,SqlKind.DML),0,20).items().size());
        }
    }

    @Test void releaseEntriesAndCountsAreExactBoundedAndSessionScoped() throws Exception {
        var paths=RuntimePaths.forTest(tempDir);
        try(var database=StateDatabase.open(paths.stateDatabase())){
            var initializer=new SessionInitializer(paths,new SessionRepository(database,paths.sessionsDirectory()));
            var session=initializer.initialize(new SessionIdentity("release-view-a","test","verified"));
            var other=initializer.initialize(new SessionIdentity("release-view-b","test","verified"));
            var repository=new ExecutionRepository(database);
            repository.appendStatement(new ExecutionRepository.StatementEventRecord(null,session.sessionId(),1,0,1L,"DDL","SUCCEEDED","LOGGING",0L,null,null,true,null,"CREATE TABLE A(P VARCHAR(9) DEFAULT 'secret')","CREATE TABLE A(P VARCHAR(9) DEFAULT 'secret')",Instant.parse("2026-07-10T06:00:01Z")));
            repository.appendStatement(new ExecutionRepository.StatementEventRecord(null,session.sessionId(),1,1,null,"DML","SUCCEEDED","EXECUTING",1L,null,null,false,"test purpose","INSERT INTO A VALUES ('hidden')",null,Instant.parse("2026-07-10T06:00:02Z")));
            repository.appendStatement(new ExecutionRepository.StatementEventRecord(null,session.sessionId(),1,2,null,"DML","FAILED","EXECUTING",0L,"42000",9,false,null,"UPDATE A SET P='failure-secret'",null,Instant.parse("2026-07-10T06:00:03Z")));
            repository.appendStatement(new ExecutionRepository.StatementEventRecord(null,other.sessionId(),1,0,1L,"DDL","SUCCEEDED","LOGGING",0L,null,null,true,null,"DROP TABLE OTHER",null,Instant.now()));

            var view=repository.releaseView(session.sessionId(),1,2);
            assertEquals(1,view.recordedCount()); assertEquals(1,view.excludedCount()); assertEquals(1,view.failedCount());
            assertEquals(2,view.entries().size()); assertTrue(view.truncated());
            assertTrue(view.entries().stream().noneMatch(e->e.rawSql().contains("OTHER")));
        }
    }

    @Test void persistsExcludedAndFailedStatementFactsWithoutReplayableSql() throws Exception {
        var paths=RuntimePaths.forTest(tempDir);
        try(var database=StateDatabase.open(paths.stateDatabase())){
            var session=new SessionInitializer(paths,new SessionRepository(database,paths.sessionsDirectory())).initialize(new SessionIdentity("facts","test","verified"));
            var repository=new ExecutionRepository(database);var id=UUID.randomUUID();
            repository.saveExecution(new ExecutionRepository.ExecutionRecord(id.toString(),UUID.randomUUID().toString(),session.sessionId(),"fp","CONSOLE","TEST","UPDATE A SET P='private'","EXECUTING","RUNNING",Instant.now(),null,null,null,null,null,null,false,null));
            var parsed=new io.dm7codex.plugin.sql.DmSqlParser().parse("UPDATE A SET P='private'");
            var error=new SafeError(UUID.randomUUID(),ExecutionStatus.EXECUTING,"Database operation failed","42000",9,false);
            var result=new StatementResult(0,SqlKind.DML,false,false,0,false,"purpose_test","auto_commit",2,java.util.Optional.of(error));
            repository.persistStatementFacts(id,session.sessionId(),session.version(),parsed,List.of(result));
            var stored=repository.findStatements(id.toString()).get(0);
            assertEquals("FAILED",stored.status());assertEquals("purpose_test",stored.exclusionReason());assertNull(stored.replayableSql());
        }
    }

    @Test void runningHistoryUsesExactPhaseFilterAndSessionCount() throws Exception {
        var paths=RuntimePaths.forTest(tempDir);try(var database=StateDatabase.open(paths.stateDatabase())){
            var initializer=new SessionInitializer(paths,new SessionRepository(database,paths.sessionsDirectory()));var session=initializer.initialize(new SessionIdentity("running-a","test","verified"));var other=initializer.initialize(new SessionIdentity("running-b","test","verified"));var repository=new ExecutionRepository(database);
            repository.started(UUID.randomUUID(),session.sessionId(),"fp",ExecutionSource.CONSOLE,java.util.Optional.empty(),"SELECT 1");var id=UUID.randomUUID();repository.started(id,session.sessionId(),"fp",ExecutionSource.MCP,java.util.Optional.of(SqlPurpose.MIGRATION),"UPDATE A SET C=1");repository.progress(id,ExecutionStatus.EXECUTING);repository.started(UUID.randomUUID(),other.sessionId(),"fp",ExecutionSource.MCP,java.util.Optional.empty(),"SELECT 1");
            assertEquals(2,repository.countRunning(session.sessionId()));assertEquals(1,repository.countRunning(other.sessionId()));var page=repository.search(new ExecutionFilter(session.sessionId(),ExecutionStatus.EXECUTING,null,null,null,null),0,20);assertEquals(1,page.items().size());assertEquals(ExecutionStatus.EXECUTING,page.items().get(0).status());repository.terminal(id,ExecutionStatus.COMPLETED,java.util.Optional.empty());assertEquals(1,repository.countRunning(session.sessionId()));assertEquals(1,repository.countRunning(other.sessionId()));
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
