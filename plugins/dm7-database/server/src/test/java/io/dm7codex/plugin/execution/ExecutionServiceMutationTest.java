package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SqlPurpose;
import io.dm7codex.plugin.sql.SqlSecurityPolicy;
import java.util.UUID;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import io.dm7codex.plugin.runtime.*;
import io.dm7codex.plugin.state.*;
import org.junit.jupiter.api.io.TempDir;
import io.dm7codex.plugin.release.ReleaseLogService;
import java.time.Duration;
import io.dm7codex.plugin.connection.DmConnectionFactory;
import io.dm7codex.plugin.connection.DriverIsolationFixture;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.nio.file.Files;

class ExecutionServiceMutationTest {
    @TempDir Path tempDir;

    @Test void parameterizedTrackedDmlBindsJdbcValueAndRecordsReplayableSql() throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve("parameterized-release"));
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            var session = new SessionInitializer(paths, sessions).initialize(
                    new SessionIdentity("parameter-thread", "codex_thread", "verified"));
            var release = new ReleaseLogService(paths, sessions, Duration.ofSeconds(2));
            var bound = new java.util.concurrent.atomic.AtomicReference<Object>();
            var prepared = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setObject" -> { bound.set(args[1]); yield null; }
                        case "executeUpdate" -> 1;
                        case "close", "cancel", "setQueryTimeout" -> null;
                        default -> null;
                    });
            var connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> prepared;
                        case "setAutoCommit", "close" -> null;
                        case "getAutoCommit" -> true;
                        case "isClosed" -> false;
                        default -> null;
                    });
            DmConnectionFactory.ConnectionOpener opener = id ->
                    new DmConnectionFactory.ManagedConnection(connection, () -> {}, "fp");
            ExecutionResult result;
            try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                    release, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
                result = service.execute(session, new ExecuteCommand(UUID.randomUUID(), UUID.randomUUID(),
                        "INSERT INTO T(C) VALUES (?)", List.of(new SqlParameter("中文", Types.NVARCHAR)),
                        SqlPurpose.MIGRATION, false, false, 30, ExecutionSource.MCP));
            }
            assertTrue(result.success(), result.toString());
            assertEquals("中文", bound.get());
            assertTrue(Files.readString(session.activeSql()).contains("VALUES (N'中文')"));
        }
    }

    @Test void callerKnownExecutionIdAllowsConcurrentCancellationWithoutEventDiscovery() throws Exception {
        UUID clientExecutionId = UUID.randomUUID();
        var opener = new TestJdbc.CancellableOpener();
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
            var future = CompletableFuture.supplyAsync(() -> service.execute(TestJdbc.session(),
                    new ExecuteCommand(UUID.randomUUID(), clientExecutionId, "UPDATE T SET C=1", List.of(),
                            SqlPurpose.TEST, true, false, 30, ExecutionSource.MCP)));
            assertTrue(opener.executing.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(service.cancel(clientExecutionId));
            opener.allowReturn.countDown();
            var result = future.join();
            assertEquals(clientExecutionId, result.executionId());
            assertEquals(ExecutionStatus.CANCELLED, result.status());
            assertTrue(opener.rolledBack.get());
        }
    }
    @Test void atomicModeRejectsDdlBeforeOpeningConnection() {
        var opener = new TestJdbc.Opener();
        var service = TestJdbc.service(opener);
        var command = new ExecuteCommand(UUID.randomUUID(), "CREATE TABLE T(ID INT)",
                SqlPurpose.MIGRATION, true, false, 60, ExecutionSource.MCP);
        assertThrows(AtomicDdlNotSupported.class, () -> service.execute(TestJdbc.session(), command));
        assertEquals(0, opener.openCount());
    }

    @Test void queuedCancellationPreventsConnectionOpen() throws Exception {
        UUID queryProfile = UUID.randomUUID(); UUID mutationProfile = UUID.randomUUID();
        var queryOpen = new java.util.concurrent.CountDownLatch(1);
        var releaseQuery = new java.util.concurrent.CountDownLatch(1);
        var mutationOpens = new java.util.concurrent.atomic.AtomicInteger();
        DmConnectionFactory.ConnectionOpener opener = id -> {
            if (id.equals(queryProfile)) {
                queryOpen.countDown();
                try { releaseQuery.await(); } catch (InterruptedException ignored) { }
                var statement = TestJdbc.statement(List.of(List.of("一")), List.of("V"));
                return new DmConnectionFactory.ManagedConnection(TestJdbc.connection(statement), () -> {}, "fp");
            }
            mutationOpens.incrementAndGet();
            return new TestJdbc.Opener().open(id);
        };
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry(), 1, 2)) {
            var query = CompletableFuture.supplyAsync(() -> service.query(TestJdbc.session(),
                    new QueryCommand(queryProfile, "SELECT 1", 1, 100, 30)));
            queryOpen.await();
            UUID queryId = service.events("session", 0).stream()
                    .filter(event -> event.status() == ExecutionStatus.QUEUED)
                    .findFirst().orElseThrow().executionId();
            var mutation = CompletableFuture.supplyAsync(() -> service.execute(TestJdbc.session(),
                    new ExecuteCommand(mutationProfile, "UPDATE T SET C=1", SqlPurpose.TEST,
                            false, false, 30)));
            UUID queuedMutation;
            do {
                queuedMutation = service.events("session", 0).stream()
                        .filter(event -> event.status() == ExecutionStatus.QUEUED)
                        .map(ExecutionEvent::executionId)
                        .filter(id -> !id.equals(queryId))
                        .findFirst().orElse(null);
                if (queuedMutation == null) Thread.onSpinWait();
            } while (queuedMutation == null);
            assertTrue(service.cancel(queuedMutation));
            releaseQuery.countDown(); query.join();
            assertEquals(ExecutionStatus.CANCELLED, mutation.join().status());
            assertEquals(0, mutationOpens.get());
        }
    }

    @Test void cancellationWhileOpenReturnsPreventsStatementExecution() throws Exception {
        var openEntered = new java.util.concurrent.CountDownLatch(1);
        var allowOpenReturn = new java.util.concurrent.CountDownLatch(1);
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        DmConnectionFactory.ConnectionOpener opener = id -> {
            openEntered.countDown();
            try { allowOpenReturn.await(); } catch (InterruptedException ignored) { }
            var statement = TestJdbc.mutationStatement(executions, Integer.MAX_VALUE);
            return new DmConnectionFactory.ManagedConnection(TestJdbc.connection(statement), () -> {}, "fp");
        };
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
            var future = CompletableFuture.supplyAsync(() -> service.execute(TestJdbc.session(),
                    new ExecuteCommand(UUID.randomUUID(), "UPDATE T SET C=1", SqlPurpose.TEST,
                            false, false, 30)));
            openEntered.await();
            UUID id = service.events("session", 0).get(0).executionId();
            service.cancel(id); allowOpenReturn.countDown();
            assertEquals(ExecutionStatus.CANCELLED, future.join().status());
            assertEquals(0, executions.get());
        }
    }

    @Test void cancellationDuringSlowCloseWinsTerminalClaim() throws Exception {
        var closeEntered = new java.util.concurrent.CountDownLatch(1);
        var allowClose = new java.util.concurrent.CountDownLatch(1);
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        var statement = TestJdbc.mutationStatement(executions, Integer.MAX_VALUE);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var connection = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> null;
                    case "createStatement" -> statement;
                    case "close" -> {
                        closeEntered.countDown();
                        try { allowClose.await(); } catch (InterruptedException ignored) { }
                        closed.set(true);
                        yield null;
                    }
                    case "isClosed" -> closed.get();
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DmConnectionFactory.ConnectionOpener opener = id ->
                new DmConnectionFactory.ManagedConnection(connection, () -> {}, "fp");
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
            var future = CompletableFuture.supplyAsync(() -> service.execute(TestJdbc.session(),
                    new ExecuteCommand(UUID.randomUUID(), "UPDATE T SET C=1", SqlPurpose.TEST,
                            false, false, 30)));
            closeEntered.await();
            UUID id = service.events("session", 0).stream()
                    .filter(event -> event.status() == ExecutionStatus.QUEUED)
                    .findFirst().orElseThrow().executionId();
            assertTrue(service.cancel(id));
            allowClose.countDown();
            var result = future.join();
            assertEquals(ExecutionStatus.CANCELLED, result.status());
            assertEquals(1, executions.get());
            assertTrue(result.statements().get(0).committed());
        }
    }

    @Test void continueOnErrorPreservesEachStatementErrorAndTerminalUsesFirstError() throws Exception {
        try (var fixture = releaseFixture("multi-error", ReleaseLogService.RecordStage.AFTER_PENDING)) {
            var opener = new TestJdbc.Opener().fingerprint("db-a").failOnStatement(2);
            try (var service = executionWithRelease(opener, fixture.release)) {
                var result = service.execute(fixture.session, new ExecuteCommand(UUID.randomUUID(),
                        "UPDATE A SET C=1; UPDATE B SET C=2", SqlPurpose.MIGRATION,
                        false, true, 60));
                var first = result.statements().get(0).error().orElseThrow();
                var second = result.statements().get(1).error().orElseThrow();
                assertEquals(ExecutionStatus.LOGGING, first.phase());
                assertNull(first.sqlState());
                assertEquals(ExecutionStatus.EXECUTING, second.phase());
                assertEquals("HY000", second.sqlState());
                assertEquals(7001, second.errorCode());
                var terminal = result.error().orElseThrow();
                assertEquals(first.phase(), terminal.phase());
                assertEquals(first.message(), terminal.message());
                assertEquals(first.sqlState(), terminal.sqlState());
                assertEquals(first.errorCode(), terminal.errorCode());
            }
        }
    }

    @Test void historyFinishAggregatesOnlyCommittedRows() throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve("committed-aggregate"));
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(paths,
                    new SessionRepository(database, paths.sessionsDirectory())).initialize(
                    new SessionIdentity("aggregate-thread", "codex_thread", "verified"));
            var history = new ExecutionRepository(database);
            UUID id = UUID.randomUUID(); UUID correlation = UUID.randomUUID();
            history.started(id, correlation, session.sessionId(), "fp", ExecutionSource.MCP,
                    Optional.of(SqlPurpose.MIGRATION), "UPDATE A; UPDATE B");
            var pending = new StatementResult(0, io.dm7codex.plugin.sql.SqlKind.DML, true, false,
                    5, false, "rolled_back", "plugin_transaction", 1, Optional.empty());
            var committed = new StatementResult(1, io.dm7codex.plugin.sql.SqlKind.DML, true, true,
                    2, false, "release_logging_failed", "auto_commit", 1, Optional.empty());
            var error = new SafeError(correlation, ExecutionStatus.LOGGING,
                    "Database operation failed", "HY000", 1, false);
            history.finish(id, List.of(pending, committed), ExecutionStatus.FAILED, Optional.of(error));
            var record = history.findExecution(id.toString()).orElseThrow();
            assertEquals(2L, record.affectedRowCount());
            assertEquals(ExecutionStatus.LOGGING.name(), record.phase());
        }
    }

    @Test void atomicCancellationAfterExecuteReturnRollsBackAndStops() throws Exception {
        var opener = new TestJdbc.CancellableOpener();
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
            var future = CompletableFuture.supplyAsync(() -> service.execute(TestJdbc.session(),
                    new ExecuteCommand(UUID.randomUUID(), "UPDATE A SET C=1; UPDATE B SET C=2",
                            SqlPurpose.TEST, true, false, 30)));
            opener.executing.await();
            UUID id = service.events("session", 0).get(0).executionId();
            assertTrue(service.cancel(id));
            opener.allowReturn.countDown();
            var result = future.join();
            assertEquals(ExecutionStatus.CANCELLED, result.status());
            assertTrue(opener.rolledBack.get());
            assertFalse(opener.committed.get());
            assertEquals(1, opener.executions.get());
        }
    }

    @Test void nonAtomicCancellationPreservesReturnedCommitAndNeverExecutesNextStatement() throws Exception {
        var opener = new TestJdbc.CancellableOpener();
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
            var future = CompletableFuture.supplyAsync(() -> service.execute(TestJdbc.session(),
                    new ExecuteCommand(UUID.randomUUID(), "UPDATE A SET C=1; UPDATE B SET C=2",
                            SqlPurpose.TEST, false, false, 30)));
            opener.executing.await();
            UUID id = service.events("session", 0).get(0).executionId();
            service.cancel(id);
            opener.allowReturn.countDown();
            var result = future.join();
            assertEquals(ExecutionStatus.CANCELLED, result.status());
            assertEquals(1, result.statements().size());
            assertTrue(result.statements().get(0).success());
            assertTrue(result.statements().get(0).committed());
            assertEquals(1, opener.executions.get());
        }
    }

    @Test void mutationBusinessFailureWithSuppressedIsolationStillRequiresRestart() {
        var statement = TestJdbc.mutationStatement(new java.util.concurrent.atomic.AtomicInteger(), 1);
        var connection = TestJdbc.connection(statement);
        DmConnectionFactory.ConnectionOpener opener = id -> new DmConnectionFactory.ManagedConnection(
                connection, () -> { throw DriverIsolationFixture.restartRequired(); }, "fp");
        ExecutionResult result;
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
            result = service.execute(TestJdbc.session(), new ExecuteCommand(UUID.randomUUID(),
                    "UPDATE T SET C=1", SqlPurpose.TEST, true, false, 30));
        }
        assertFalse(result.success());
        assertTrue(result.error().orElseThrow().restartRequired());
        assertEquals("HY000", result.error().orElseThrow().sqlState());
        assertFalse(result.statements().get(0).success());
    }

    @Test void nonAtomicLoggingFailurePreservesCommittedDmlFact() throws Exception {
        try (var fixture = releaseFixture("logging-dml", ReleaseLogService.RecordStage.AFTER_PENDING)) {
            var opener = new TestJdbc.Opener().fingerprint("db-a").failOnStatement(Integer.MAX_VALUE);
            try (var service = executionWithRelease(opener, fixture.release)) {
                var result = service.execute(fixture.session, new ExecuteCommand(UUID.randomUUID(),
                        "UPDATE T SET C=1", SqlPurpose.MIGRATION, false, false, 60));
                assertFalse(result.success());
                var statement = result.statements().get(0);
                assertTrue(statement.success());
                assertTrue(statement.committed());
                assertEquals(1, statement.rowCount());
                assertFalse(statement.recorded());
                assertEquals(ExecutionStatus.LOGGING, statement.error().orElseThrow().phase());
            }
        }
    }

    @Test void nonAtomicLoggingFailurePreservesDatabaseManagedDdlFact() throws Exception {
        try (var fixture = releaseFixture("logging-ddl", ReleaseLogService.RecordStage.BEFORE_FINALIZE)) {
            var opener = new TestJdbc.Opener().fingerprint("db-a").failOnStatement(Integer.MAX_VALUE);
            try (var service = executionWithRelease(opener, fixture.release)) {
                var result = service.execute(fixture.session, new ExecuteCommand(UUID.randomUUID(),
                        "CREATE TABLE T(ID INT)", SqlPurpose.MIGRATION, false, false, 60));
                var statement = result.statements().get(0);
                assertTrue(statement.success()); assertTrue(statement.committed());
                assertEquals("database_managed", statement.commitBehavior());
                assertFalse(statement.recorded());
                assertEquals(ExecutionStatus.LOGGING, statement.error().orElseThrow().phase());
            }
        }
    }

    @Test void atomicLoggingFailurePreservesEveryCommittedStatementFact() throws Exception {
        try (var fixture = releaseFixture("logging-atomic", ReleaseLogService.RecordStage.AFTER_PENDING)) {
            var opener = new TestJdbc.Opener().fingerprint("db-a").failOnStatement(Integer.MAX_VALUE);
            try (var service = executionWithRelease(opener, fixture.release)) {
                var result = service.execute(fixture.session, new ExecuteCommand(UUID.randomUUID(),
                        "UPDATE A SET C=1; UPDATE B SET C=2", SqlPurpose.MIGRATION, true, false, 60));
                assertFalse(result.success());
                assertEquals(2, result.statements().size());
                assertTrue(result.statements().stream().allMatch(StatementResult::success));
                assertTrue(result.statements().stream().allMatch(StatementResult::committed));
                assertFalse(result.statements().get(0).recorded());
                assertEquals(ExecutionStatus.LOGGING,
                        result.statements().get(0).error().orElseThrow().phase());
                assertTrue(opener.committed());
                assertFalse(opener.rolledBack());
            }
        }
    }

    @Test void driverIsolationFailureDuringSuccessfulMutationCloseRequiresRestart() {
        var statement = TestJdbc.mutationStatement(new java.util.concurrent.atomic.AtomicInteger(),
                Integer.MAX_VALUE);
        var connection = TestJdbc.connection(statement);
        DmConnectionFactory.ConnectionOpener opener = id -> new DmConnectionFactory.ManagedConnection(
                connection, () -> {
                    assertTrue(connection.isClosed(), "connection must close before driver handle");
                    throw DriverIsolationFixture.restartRequired();
                }, "fp");
        ExecutionResult result;
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry())) {
            result = service.execute(TestJdbc.session(), new ExecuteCommand(UUID.randomUUID(),
                    "UPDATE T SET C=1", SqlPurpose.TEST, false, false, 30));
        }
        assertFalse(result.success());
        assertTrue(result.error().orElseThrow().restartRequired());
    }

    @Test void rollbackProducesNoReleaseEntries() throws Exception {
        try (var fixture = releaseFixture("rollback-release")) {
            var opener = new TestJdbc.Opener().fingerprint("db-a").failOnStatement(2);
            try (var service = executionWithRelease(opener, fixture.release)) {
                var result = service.execute(fixture.session, new ExecuteCommand(UUID.randomUUID(),
                        "UPDATE A SET C=1; UPDATE B SET C=2", SqlPurpose.MIGRATION,
                        true, false, 60));
                assertFalse(result.success());
                assertEquals(0, fixture.release.inspect(fixture.session).statementCount());
            }
        }
    }

    @Test void nonAtomicDdlIsLoggedBeforeFollowingFailure() throws Exception {
        try (var fixture = releaseFixture("ddl-release")) {
            var opener = new TestJdbc.Opener().fingerprint("db-a").failOnStatement(2);
            try (var service = executionWithRelease(opener, fixture.release)) {
                var result = service.execute(fixture.session, new ExecuteCommand(UUID.randomUUID(),
                        "CREATE TABLE T(ID INT); UPDATE T SET ID=1", SqlPurpose.MIGRATION,
                        false, false, 60));
                assertFalse(result.success());
                assertEquals(1, fixture.release.inspect(fixture.session).statementCount());
                assertTrue(result.statements().get(0).recorded());
            }
        }
    }

    @Test void sameSqlInDifferentExecutionsIsRecordedTwiceAndCrossDatabasePreflightBlocksExecution()
            throws Exception {
        try (var fixture = releaseFixture("repeat-cross-db")) {
            var openerA = new TestJdbc.Opener().fingerprint("db-a").failOnStatement(Integer.MAX_VALUE);
            try (var service = executionWithRelease(openerA, fixture.release)) {
                var command = new ExecuteCommand(UUID.randomUUID(), "UPDATE T SET C=1",
                        SqlPurpose.MIGRATION, false, false, 60);
                assertTrue(service.execute(fixture.session, command).success());
                assertTrue(service.execute(fixture.session, command).success());
            }
            assertEquals(2, fixture.release.inspect(fixture.session).statementCount());
            var openerB = new TestJdbc.Opener().fingerprint("db-b").failOnStatement(Integer.MAX_VALUE);
            try (var service = executionWithRelease(openerB, fixture.release)) {
                assertFalse(service.execute(fixture.session, new ExecuteCommand(UUID.randomUUID(),
                        "UPDATE T SET C=2", SqlPurpose.MIGRATION, false, false, 60)).success());
            }
            assertEquals(0, openerB.executionCount());
            assertEquals(2, fixture.release.inspect(fixture.session).statementCount());
        }
    }

    @Test void statementLedgerSeparatesRecordedExcludedFailedAndRolledBackFacts() throws Exception {
        try(var fixture=releaseFixture("statement-ledger")){
            var repository=new ExecutionRepository(fixture.database);
            var eligible=new TestJdbc.Opener().fingerprint("db-a");
            try(var service=new ExecutionService(eligible,new DmSqlParser(),new SqlSecurityPolicy(),fixture.release,repository,new ExecutionEventBus(50),new ExecutionRegistry())){
                assertTrue(service.execute(fixture.session,new ExecuteCommand(UUID.randomUUID(),"UPDATE A SET C=1",SqlPurpose.MIGRATION,false,false,60)).success());
            }
            var excluded=new TestJdbc.Opener().fingerprint("db-a").failOnStatement(2);
            try(var service=new ExecutionService(excluded,new DmSqlParser(),new SqlSecurityPolicy(),fixture.release,repository,new ExecutionEventBus(50),new ExecutionRegistry())){
                service.execute(fixture.session,new ExecuteCommand(UUID.randomUUID(),"UPDATE A SET C=2; UPDATE B SET C=3",SqlPurpose.TEST,false,true,60));
            }
            var rolledBack=new TestJdbc.Opener().fingerprint("db-a").failOnStatement(2);
            try(var service=new ExecutionService(rolledBack,new DmSqlParser(),new SqlSecurityPolicy(),fixture.release,repository,new ExecutionEventBus(50),new ExecutionRegistry())){
                service.execute(fixture.session,new ExecuteCommand(UUID.randomUUID(),"UPDATE C SET C=4; UPDATE D SET C=5",SqlPurpose.MIGRATION,true,false,60));
            }
            var view=repository.releaseView(fixture.session.sessionId(),1,20);
            assertEquals(1,view.recordedCount());assertEquals(1,view.excludedCount());assertEquals(3,view.failedCount());
            assertEquals(1,view.entries().stream().filter(e->e.recorded()&&e.sequence()!=null).count());
            assertTrue(view.entries().stream().filter(e->!e.recorded()).allMatch(e->e.rawSql()!=null));
            assertEquals(1,fixture.release.inspect(fixture.session).statementCount());
        }
    }

    private ExecutionService executionWithRelease(TestJdbc.Opener opener, ReleaseLogService release) {
        return new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(), release,
                null, new ExecutionEventBus(50), new ExecutionRegistry());
    }

    private ReleaseFixture releaseFixture(String name) throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve(name));
        var database = StateDatabase.open(paths.stateDatabase());
        var sessions = new SessionRepository(database, paths.sessionsDirectory());
        var session = new SessionInitializer(paths, sessions).initialize(
                new SessionIdentity("thread-" + name, "codex_thread", "verified"));
        return new ReleaseFixture(database, session,
                new ReleaseLogService(paths, sessions, Duration.ofSeconds(2)));
    }

    private ReleaseFixture releaseFixture(String name, ReleaseLogService.RecordStage failStage)
            throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve(name));
        var database = StateDatabase.open(paths.stateDatabase());
        var sessions = new SessionRepository(database, paths.sessionsDirectory());
        var session = new SessionInitializer(paths, sessions).initialize(
                new SessionIdentity("thread-" + name, "codex_thread", "verified"));
        var release = new ReleaseLogService(paths, sessions, Duration.ofSeconds(2),
                new SqlSecurityPolicy(), stage -> { if (stage == failStage) throw new java.io.IOException("fault"); });
        return new ReleaseFixture(database, session, release);
    }

    private record ReleaseFixture(StateDatabase database, SessionState session,
            ReleaseLogService release) implements AutoCloseable {
        @Override public void close() { database.close(); }
    }

    @Test void operationIdsAreStablePerExecutionAndDistinctAcrossStatementsAndExecutions() {
        var statements = new DmSqlParser().parse("UPDATE A SET C=1; UPDATE A SET C=1");
        UUID first = UUID.randomUUID();
        assertEquals(ExecutionService.operationId(first, statements.get(0)),
                ExecutionService.operationId(first, statements.get(0)));
        assertNotEquals(ExecutionService.operationId(first, statements.get(0)),
                ExecutionService.operationId(first, statements.get(1)));
        assertNotEquals(ExecutionService.operationId(first, statements.get(0)),
                ExecutionService.operationId(UUID.randomUUID(), statements.get(0)));
    }

    @Test void nonAtomicDdlSuccessRemainsCommittedWhenFollowingDmlFailsAndStops() {
        var opener = new TestJdbc.Opener().failOnStatement(2);
        var result = TestJdbc.service(opener).execute(TestJdbc.session(), new ExecuteCommand(
                UUID.randomUUID(), "CREATE TABLE T(ID INT); UPDATE T SET ID=1; UPDATE T SET ID=2",
                SqlPurpose.TEST, false, false, 60));
        assertEquals(2, result.statements().size());
        assertTrue(result.statements().get(0).committed());
        assertEquals("database_managed", result.statements().get(0).commitBehavior());
        assertFalse(result.statements().get(1).success());
    }

    @Test void secretBearingSqlIsRejectedWithoutHistoryPersistence() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(paths,
                    new SessionRepository(database, paths.sessionsDirectory())).initialize(
                    new SessionIdentity("secret-history", "codex_thread", "verified"));
            var repository = new ExecutionRepository(database);
            var opener = new TestJdbc.Opener();
            try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                    null, repository, new ExecutionEventBus(20), new ExecutionRegistry())) {
                assertThrows(io.dm7codex.plugin.sql.SecretBearingSqlException.class,
                        () -> service.execute(session, new ExecuteCommand(UUID.randomUUID(),
                                "CREATE USER U IDENTIFIED BY secret", SqlPurpose.MIGRATION,
                                false, false, 30)));
            }
            assertEquals(0, repository.search(new ExecutionFilter(session.sessionId(), null,
                    null, null, null, null), 0, 20).items().size());
            assertEquals(0, opener.openCount());
        }
    }

    @Test void executionHistorySearchesSanitizedTerminalRecords() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(paths,
                    new SessionRepository(database, paths.sessionsDirectory())).initialize(
                    new SessionIdentity("history-thread", "codex_thread", "verified"));
            var repository = new ExecutionRepository(database);
            UUID id = UUID.randomUUID();
            repository.started(id, session.sessionId(), "fp", ExecutionSource.MCP,
                    Optional.of(SqlPurpose.MIGRATION), "UPDATE T SET C=1");
            repository.progress(id, ExecutionStatus.EXECUTING);
            repository.terminal(id, ExecutionStatus.FAILED,
                    Optional.of(new SafeError(UUID.randomUUID(), ExecutionStatus.EXECUTING,
                            "Database operation failed", "HY000", 7001, false)));
            var page = repository.search(new ExecutionFilter(session.sessionId(),
                    ExecutionStatus.FAILED, null, null, null, null), 0, 20);
            assertEquals(1, page.items().size());
            assertEquals("HY000", repository.findExecution(id.toString()).orElseThrow().sqlState());
            assertFalse(repository.findExecution(id.toString()).orElseThrow().errorMessage().contains("password"));
        }
    }

    @Test void trackedAnonymousBlockIsRejectedBeforeExecution() {
        var opener = new TestJdbc.Opener();
        var service = TestJdbc.service(opener);
        var command = new ExecuteCommand(UUID.randomUUID(),
                "BEGIN EXECUTE IMMEDIATE 'DROP TABLE T'; END;", SqlPurpose.MIGRATION,
                false, false, 60, ExecutionSource.MCP);
        assertThrows(UntrackableMutationException.class,
                () -> service.execute(TestJdbc.session(), command));
        assertEquals(0, opener.openCount());
    }

    @Test void atomicFailureRollsBackAndReturnsNoCommittedStatements() {
        var opener = new TestJdbc.Opener().failOnStatement(2);
        var result = TestJdbc.service(opener).execute(TestJdbc.session(), new ExecuteCommand(
                UUID.randomUUID(), "UPDATE A SET C=1; UPDATE B SET C=2", SqlPurpose.TEST,
                true, false, 60));
        assertFalse(result.success());
        assertTrue(opener.rolledBack());
        assertFalse(opener.committed());
        assertTrue(result.statements().stream().noneMatch(StatementResult::committed));
    }

    @Test void nonAtomicContinuePreservesSuccessAndRunsFollowingStatement() {
        var opener = new TestJdbc.Opener().failOnStatement(2);
        var result = TestJdbc.service(opener).execute(TestJdbc.session(), new ExecuteCommand(
                UUID.randomUUID(), "UPDATE A SET C=1; UPDATE B SET C=2; UPDATE C SET C=3",
                SqlPurpose.TEST, false, true, 60));
        assertFalse(result.success());
        assertEquals(3, result.statements().size());
        assertTrue(result.statements().get(0).success());
        assertFalse(result.statements().get(1).success());
        assertTrue(result.statements().get(2).success());
    }
}
