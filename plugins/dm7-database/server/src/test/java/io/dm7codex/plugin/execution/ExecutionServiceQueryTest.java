package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.List;
import javax.sql.rowset.serial.SerialClob;
import javax.sql.rowset.serial.SerialBlob;
import io.dm7codex.plugin.connection.DmConnectionFactory;
import io.dm7codex.plugin.connection.DriverIsolationFixture;
import io.dm7codex.plugin.runtime.*;
import io.dm7codex.plugin.state.*;
import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SqlSecurityPolicy;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

class ExecutionServiceQueryTest {
    @TempDir Path tempDir;

    @Test void typedParametersUsePreparedStatementAndClientExecutionId() {
        UUID executionId = UUID.randomUUID();
        var bound = new AtomicReference<Object>();
        var statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setObject" -> { bound.set(args[1]); yield null; }
                    case "executeQuery" -> TestJdbc.resultSet(List.of(List.of("中文")), List.of("V"));
                    case "close", "setQueryTimeout", "setMaxRows", "setFetchSize", "cancel" -> null;
                    default -> null;
                });
        var connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> statement;
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> null;
                });
        DmConnectionFactory.ConnectionOpener opener = id ->
                new DmConnectionFactory.ManagedConnection(connection, () -> {}, "fp");
        QueryResult result;
        try (var service = TestJdbc.service(opener)) {
            result = service.query(TestJdbc.session(), new QueryCommand(UUID.randomUUID(), executionId,
                    "SELECT ? AS V", List.of(new SqlParameter("中文", Types.NVARCHAR)),
                    10, 1_000, 30, ExecutionSource.MCP));
        }
        assertTrue(result.success(), result.toString());
        assertEquals(executionId, result.executionId());
        assertEquals("中文", bound.get());
    }

    @Test void queryTimeoutForceClosesJdbcWorkAndPersistsOneTerminalFailureWithinBound() throws Exception {
        var released = new CountDownLatch(1);
        var closeCalls = new AtomicInteger();
        var jdbcTimeout = new AtomicInteger();
        var statement = (java.sql.Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{java.sql.Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        released.await(10, TimeUnit.SECONDS);
                        throw new java.sql.SQLException("bounded test query stopped", "57014", 0);
                    }
                    case "cancel" -> null; // model a driver that accepts but ignores cancellation
                    case "close" -> {
                        closeCalls.incrementAndGet();
                        released.await(10, TimeUnit.SECONDS);
                        yield null;
                    }
                    case "setQueryTimeout" -> { jdbcTimeout.set((Integer)args[0]); yield null; }
                    case "setMaxRows", "setFetchSize" -> null;
                    default -> null;
                });
        var connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "close" -> { released.countDown(); yield null; }
                    case "isClosed" -> false;
                    default -> null;
                });
        DmConnectionFactory.ConnectionOpener opener = new DmConnectionFactory.ConnectionOpener() {
            @Override public DmConnectionFactory.ManagedConnection open(UUID id) {
                return new DmConnectionFactory.ManagedConnection(connection, () -> {}, "fp");
            }
            @Override public DmConnectionFactory.ConnectionLimits limits(UUID id) {
                return new DmConnectionFactory.ConnectionLimits(10, 1_000, 1);
            }
        };
        var fixture = historyFixture("wall-clock-timeout");
        var events = new ExecutionEventBus(20);
        UUID executionId = UUID.randomUUID();
        long started = System.nanoTime();
        try (fixture.database) {
            QueryResult result;
            try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                    null, fixture.history, events, new ExecutionRegistry())) {
                result = service.query(fixture.session, new QueryCommand(UUID.randomUUID(), executionId,
                        "SELECT V FROM T", List.of(), 1, 100, 30, ExecutionSource.MCP));
            }
            assertFalse(result.success());
            assertEquals(ExecutionStatus.EXECUTING, result.error().orElseThrow().phase());
            assertEquals("Execution timed out", result.error().orElseThrow().message());
            assertEquals(70005, result.error().orElseThrow().errorCode());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000,
                    "query did not honor its wall-clock timeout");
            assertTrue(closeCalls.get() > 0, "timeout did not force-close ignored cancellation");
            assertEquals(1, jdbcTimeout.get());
            var record = fixture.history.findExecution(executionId.toString()).orElseThrow();
            assertEquals(ExecutionStatus.FAILED.name(), record.status());
            assertNotNull(record.completedAt());
            long terminalEvents = events.events(fixture.session.sessionId(), 0).stream()
                    .filter(event -> List.of(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED,
                            ExecutionStatus.CANCELLED, ExecutionStatus.REJECTED).contains(event.status())).count();
            assertEquals(1, terminalEvents);
        }
    }

    @Test void permanentlyBlockedJdbcCleanupStillFinalizesTimeoutExactlyOnceWithinBound() throws Exception {
        var never = new CountDownLatch(1);
        var statement = (java.sql.Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{java.sql.Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery", "cancel", "close" -> {
                        awaitIgnoringInterrupts(never);
                        yield null;
                    }
                    case "setQueryTimeout", "setMaxRows", "setFetchSize" -> null;
                    default -> null;
                });
        var connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "close" -> { awaitIgnoringInterrupts(never); yield null; }
                    case "isClosed" -> false;
                    default -> null;
                });
        DmConnectionFactory.ConnectionOpener opener = new DmConnectionFactory.ConnectionOpener() {
            @Override public DmConnectionFactory.ManagedConnection open(UUID id) {
                return new DmConnectionFactory.ManagedConnection(connection, () -> {}, "fp");
            }
            @Override public DmConnectionFactory.ConnectionLimits limits(UUID id) {
                return new DmConnectionFactory.ConnectionLimits(10, 1_000, 1);
            }
        };
        var fixture = historyFixture("permanent-query-cleanup-timeout");
        var events = new ExecutionEventBus(20); var registry = new ExecutionRegistry();
        UUID executionId = UUID.randomUUID();
        var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, fixture.history, events, registry);
        var baselineThreads = java.util.Set.copyOf(Thread.getAllStackTraces().keySet());
        java.util.Set<Thread> ownedThreads = java.util.Set.of();
        long started = System.nanoTime();
        try (fixture.database) {
            try {
                QueryResult result = service.query(fixture.session, new QueryCommand(UUID.randomUUID(), executionId,
                        "SELECT V FROM T", List.of(), 1, 100, 30, ExecutionSource.MCP));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000,
                        "final timeout finalization exceeded its bound");
                assertFalse(result.success());
                assertEquals(ExecutionStatus.EXECUTING, result.error().orElseThrow().phase());
                assertEquals(70005, result.error().orElseThrow().errorCode());
                assertTrue(result.error().orElseThrow().restartRequired());
                var record = fixture.history.findExecution(executionId.toString()).orElseThrow();
                assertEquals(ExecutionStatus.FAILED.name(), record.status());
                assertNotNull(record.completedAt());
                assertEquals(0, registry.activeCount());
                ownedThreads = executionThreadsCreatedAfter(baselineThreads);
                assertFalse(ownedThreads.isEmpty());
                assertThrows(ExecutionCleanupTimeoutException.class, service::close);
                assertThrows(ExecutionCleanupTimeoutException.class, service::close,
                        "a repeated close must retain the fail-closed cleanup warning");
            } finally {
                never.countDown();
            }
            assertTrue(awaitTermination(ownedThreads), "abandoned query cleanup threads did not terminate after release");
            assertEquals(1, events.events(fixture.session.sessionId(), 0).stream()
                    .filter(event -> event.executionId().equals(executionId))
                    .filter(event -> List.of(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED,
                            ExecutionStatus.CANCELLED, ExecutionStatus.REJECTED).contains(event.status())).count());
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (true) try { latch.await(); return; }
        catch (InterruptedException ignored) { }
    }

    private static java.util.Set<Thread> executionThreadsCreatedAfter(java.util.Set<Thread> baseline) {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> !baseline.contains(thread))
                .filter(thread -> thread.getName().startsWith("dm7-execution")
                        || thread.getName().startsWith("dm7-jdbc-")
                        || thread.getName().startsWith("dm7-cancellation-closer"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean awaitTermination(java.util.Set<Thread> threads) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if (threads.stream().noneMatch(Thread::isAlive)) return true;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Test void connectionFailureIsPersistedWithSharedCorrelationAndTruePhase() throws Exception {
        var fixture = historyFixture("connect-failure");
        try (fixture.database) {
            DmConnectionFactory.ConnectionOpener opener = id -> { throw new java.sql.SQLException("secret", "08001", 88); };
            QueryResult result;
            try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                    null, fixture.history, new ExecutionEventBus(20), new ExecutionRegistry())) {
                result = service.query(fixture.session, new QueryCommand(UUID.randomUUID(),
                        "SELECT 1", 10, 1000, 30));
            }
            var record = fixture.history.findExecution(result.executionId().toString()).orElseThrow();
            assertEquals(result.error().orElseThrow().correlationId().toString(), record.correlationId());
            assertEquals(ExecutionStatus.CONNECTING.name(), record.phase());
            assertEquals("unknown", record.connectionFingerprint());
            assertEquals("08001", record.sqlState());
        }
    }

    @Test void queuedQueryTimeoutPersistsTerminalFactsWithoutOpeningConnectionOrLeakingRegistryEntry() throws Exception {
        UUID blockingProfile = UUID.randomUUID(), queuedProfile = UUID.randomUUID(), queuedExecution = UUID.randomUUID();
        var firstEntered = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1);
        var queuedOpens = new AtomicInteger();
        DmConnectionFactory.ConnectionOpener opener = new DmConnectionFactory.ConnectionOpener() {
            @Override public DmConnectionFactory.ManagedConnection open(UUID id) throws java.sql.SQLException {
                if (id.equals(blockingProfile)) {
                    firstEntered.countDown();
                    try { releaseFirst.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                } else queuedOpens.incrementAndGet();
                return new DmConnectionFactory.ManagedConnection(
                        TestJdbc.connection(TestJdbc.statement(List.of(List.of(1)), List.of("V"))), () -> {}, "fp");
            }
            @Override public DmConnectionFactory.ConnectionLimits limits(UUID id) {
                return new DmConnectionFactory.ConnectionLimits(10, 1_000, id.equals(queuedProfile) ? 1 : 30);
            }
        };
        var fixture = historyFixture("queued-timeout"); var events = new ExecutionEventBus(20);
        var registry = new ExecutionRegistry();
        try (fixture.database; var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, fixture.history, events, registry, 1, 4)) {
            var first = CompletableFuture.supplyAsync(() -> service.query(fixture.session,
                    new QueryCommand(blockingProfile, "SELECT V FROM T", 1, 100, 30)));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            QueryResult queued = service.query(fixture.session, new QueryCommand(queuedProfile, queuedExecution,
                    "SELECT V FROM T", List.of(), 1, 100, 30, ExecutionSource.MCP));
            assertFalse(queued.success()); assertEquals(70005, queued.error().orElseThrow().errorCode());
            assertEquals(ExecutionStatus.QUEUED, queued.error().orElseThrow().phase());
            assertEquals(0, queuedOpens.get());
            var record = fixture.history.findExecution(queuedExecution.toString()).orElseThrow();
            assertEquals(ExecutionStatus.FAILED.name(), record.status()); assertNotNull(record.completedAt());
            long terminal = events.events(fixture.session.sessionId(), 0).stream()
                    .filter(event -> event.executionId().equals(queuedExecution))
                    .filter(event -> List.of(ExecutionStatus.FAILED, ExecutionStatus.CANCELLED,
                            ExecutionStatus.COMPLETED, ExecutionStatus.REJECTED).contains(event.status())).count();
            assertEquals(1, terminal);
            releaseFirst.countDown(); assertTrue(first.join().success());
        } finally { releaseFirst.countDown(); }
        assertEquals(0, registry.activeCount());
    }

    @Test void oneByteBlobWithMaximumBudgetDoesNotRequireBudgetSizedOutput() throws Exception {
        var opener = new TestJdbc.Opener().queryRows(
                List.of(List.of(new SerialBlob(new byte[]{1}))), List.of("B"));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT B FROM T", 1,
                        ExecutionModels.MAX_BYTES, 30));
        assertEquals("base64:AQ==", result.rows().get(0).get("B"));
        assertTrue(result.bytes() < 32);
    }

    @Test void closeFailureHistoryRetainsRowsReadBeforeTerminalFailure() throws Exception {
        var fixture = historyFixture("query-close-history");
        try (fixture.database) {
            var statement = TestJdbc.statement(List.of(List.of("一"), List.of("二")), List.of("V"));
            var connection = TestJdbc.connection(statement);
            DmConnectionFactory.ConnectionOpener opener = id -> new DmConnectionFactory.ManagedConnection(
                    connection, () -> { throw DriverIsolationFixture.restartRequired(); }, "fp");
            QueryResult result;
            try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                    null, fixture.history, new ExecutionEventBus(20), new ExecutionRegistry())) {
                result = service.query(fixture.session, new QueryCommand(UUID.randomUUID(),
                        "SELECT V FROM T", 10, 1000, 30));
            }
            var record = fixture.history.findExecution(result.executionId().toString()).orElseThrow();
            assertEquals(2L, record.returnedRowCount());
            assertNull(record.affectedRowCount());
            assertEquals(ExecutionStatus.EXECUTING.name(), record.phase());
        }
    }

    @Test void oversizedColumnMetadataReturnsSafeLimitErrorWithoutUnbudgetedColumns() {
        String label = "超长列名".repeat(20);
        var opener = new TestJdbc.Opener().queryRows(List.of(List.of("值")), List.of(label));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT V FROM T", 1, 10, 30));
        assertFalse(result.success());
        assertTrue(result.columns().isEmpty());
        assertTrue(result.rows().isEmpty());
        assertEquals(0, result.bytes());
        assertEquals(70003, result.error().orElseThrow().errorCode());
    }

    @Test void businessFailureWithSuppressedIsolationStillRequiresRestart() {
        var connection = TestJdbc.connection(TestJdbc.failingQueryStatement());
        DmConnectionFactory.ConnectionOpener opener = id -> new DmConnectionFactory.ManagedConnection(
                connection, () -> { throw DriverIsolationFixture.restartRequired(); }, "fp");
        QueryResult result;
        try (var service = TestJdbc.service(opener)) {
            result = service.query(TestJdbc.session(), new QueryCommand(UUID.randomUUID(),
                    "SELECT V FROM T", 1, 100, 30));
        }
        assertFalse(result.success());
        assertEquals(ExecutionStatus.EXECUTING, result.error().orElseThrow().phase());
        assertTrue(result.error().orElseThrow().restartRequired());
        assertEquals("HY000", result.error().orElseThrow().sqlState());
    }

    @Test void queryHistoryStoresReturnedRowsNotAffectedRows() throws Exception {
        var fixture = historyFixture("query-count");
        try (fixture.database) {
            var opener = new TestJdbc.Opener().queryRows(List.of(List.of("一"), List.of("二")), List.of("V"));
            QueryResult result;
            try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                    null, fixture.history, new ExecutionEventBus(20), new ExecutionRegistry())) {
                result = service.query(fixture.session, new QueryCommand(UUID.randomUUID(),
                        "SELECT V FROM T", 10, 1000, 30));
            }
            var record = fixture.history.findExecution(result.executionId().toString()).orElseThrow();
            assertEquals(2L, record.returnedRowCount());
            assertNull(record.affectedRowCount());
        }
    }

    private HistoryFixture historyFixture(String name) throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve(name));
        var database = StateDatabase.open(paths.stateDatabase());
        var sessions = new SessionRepository(database, paths.sessionsDirectory());
        var session = new SessionInitializer(paths, sessions).initialize(
                new SessionIdentity("thread-" + name, "codex_thread", "verified"));
        return new HistoryFixture(database, session, new ExecutionRepository(database));
    }

    private record HistoryFixture(StateDatabase database, SessionState session,
            ExecutionRepository history) {}
    @Test void ordinaryCloseFailureIsSafelyRedacted() {
        var statement = TestJdbc.statement(List.of(List.of("一")), List.of("V"));
        var connection = TestJdbc.connection(statement);
        DmConnectionFactory.ConnectionOpener opener = id -> new DmConnectionFactory.ManagedConnection(
                connection, () -> { throw new java.sql.SQLException("password=leaked", "HY001", 99); }, "fp");
        QueryResult result;
        try (var service = TestJdbc.service(opener)) {
            result = service.query(TestJdbc.session(), new QueryCommand(UUID.randomUUID(),
                    "SELECT V FROM T", 1, 100, 30));
        }
        assertFalse(result.success());
        assertEquals("Database operation failed", result.error().orElseThrow().message());
        assertEquals("HY001", result.error().orElseThrow().sqlState());
        assertFalse(result.error().orElseThrow().restartRequired());
        assertFalse(result.error().orElseThrow().message().contains("password"));
    }

    @Test void nullValuesAndFullColumnMetadataArePreservedInOrder() {
        var opener = new TestJdbc.Opener().queryRows(
                java.util.Collections.singletonList(java.util.Arrays.asList(null, "二")),
                List.of("重复", "重复"));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT A,B FROM T", 10, 1000, 30));
        assertTrue(result.success());
        assertNull(result.rows().get(0).get("重复"));
        assertEquals(List.of("重复", "重复#2"), new java.util.ArrayList<>(result.rows().get(0).keySet()));
        assertEquals("重复", result.columns().get(0).outputLabel());
        assertEquals("重复", result.columns().get(0).originalLabel());
        assertEquals("重复", result.columns().get(0).originalName());
        assertEquals(java.sql.Types.VARCHAR, result.columns().get(0).jdbcType());
        assertEquals("VARCHAR", result.columns().get(0).typeName());
    }

    @Test void queryRejectsMutationBeforeConnection() {
        var opener = new TestJdbc.Opener();
        var service = TestJdbc.service(opener);
        assertThrows(IllegalArgumentException.class, () -> service.query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "DELETE FROM T", 100, 1000, 30,
                        ExecutionSource.MCP)));
        assertEquals(0, opener.openCount());
    }

    @Test void driverIsolationFailureDuringSuccessfulQueryCloseRequiresRestart() {
        var statement = TestJdbc.statement(List.of(List.of("一")), List.of("V"));
        var connection = TestJdbc.connection(statement);
        DmConnectionFactory.ConnectionOpener opener = id -> new DmConnectionFactory.ManagedConnection(
                connection, () -> {
                    assertTrue(connection.isClosed(), "connection must close before driver handle");
                    throw DriverIsolationFixture.restartRequired();
                }, "fp");
        QueryResult result;
        try (var service = TestJdbc.service(opener)) {
            result = service.query(TestJdbc.session(), new QueryCommand(UUID.randomUUID(),
                    "SELECT V FROM T", 1, 100, 30));
        }
        assertFalse(result.success());
        assertTrue(result.error().orElseThrow().restartRequired());
    }

    @Test void commandProfileAndGlobalLimitsAreClampedIntoJdbcSettings() {
        var opener = new TestJdbc.Opener().queryRows(List.of(List.of("一")), List.of("V"))
                .limits(7, 100, 9);
        try (var service = TestJdbc.service(opener)) {
            service.query(TestJdbc.session(), new QueryCommand(UUID.randomUUID(),
                    "SELECT V FROM T", 20, 200, 30));
        }
        assertEquals(9, opener.timeout());
        assertEquals(8, opener.maxRows());
        assertEquals(8, opener.fetchSize());
    }

    @Test void clobSurrogateAcrossReaderBufferBoundaryHasExactUtf8Count() throws Exception {
        String value = "x".repeat(4095) + "😀";
        var opener = new TestJdbc.Opener().queryRows(
                List.of(List.of(new SerialClob(value.toCharArray()))), List.of("C"));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT C FROM T", 1, 5000, 30));
        assertEquals(value, result.rows().get(0).get("C"));
        assertEquals(4100, result.bytes());
    }

    @Test void chineseQueryResultsRemainExactAndBounded() throws Exception {
        var opener = new TestJdbc.Opener().queryRows(List.of(List.of("达梦数据库", "第二值")),
                List.of("中文列", "中文列"));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT NAME FROM T", 10, 1_000_000, 30));
        assertEquals("达梦数据库", result.rows().get(0).get("中文列"));
        assertEquals("第二值", result.rows().get(0).get("中文列#2"));
        assertFalse(result.truncated());
        assertTrue(opener.closed());
    }

    @Test void readsOneExtraRowToReportTruncation() {
        var opener = new TestJdbc.Opener().queryRows(
                List.of(List.of("一"), List.of("二")), List.of("值"));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT V FROM T", 1, 1000, 30));
        assertEquals(1, result.rows().size());
        assertTrue(result.truncated());
    }

    @Test void byteLimitDoesNotSplitSurrogatePairsAndBoundsLargeObjects() throws Exception {
        var textOpener = new TestJdbc.Opener().queryRows(
                List.of(List.of(new SerialClob("A😀B".toCharArray()))), List.of("C"));
        var text = TestJdbc.service(textOpener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT C FROM T", 1, 6, 30));
        assertEquals("A😀", text.rows().get(0).get("C"));
        assertTrue(text.truncated());
        assertEquals(6, text.bytes());

        var binaryOpener = new TestJdbc.Opener().queryRows(
                List.of(List.of(new SerialBlob(new byte[100]))), List.of("B"));
        var binary = TestJdbc.service(binaryOpener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT B FROM T", 1, 16, 30));
        assertTrue(((String) binary.rows().get(0).get("B")).startsWith("base64:"));
        assertTrue(binary.bytes() <= 16);
        assertTrue(binary.truncated());
    }

    @Test void scalarRowIsOmittedAtomicallyWhenRemainingBudgetCannotFitAllColumns() {
        var opener = new TestJdbc.Opener().queryRows(List.of(List.of(123, true)), List.of("A", "B"));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT A,B FROM T", 1, 7, 30));
        assertTrue(result.truncated());
        assertTrue(result.rows().isEmpty());
        assertTrue(result.bytes() <= 7);
    }

    @Test void hugeBinaryUsesBoundedStreamingOutput() {
        var opener = new TestJdbc.Opener().queryRows(
                List.of(List.of(new byte[20 * 1024 * 1024])), List.of("B"));
        var result = TestJdbc.service(opener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT B FROM T", 1, 100, 30));
        assertTrue(result.truncated());
        assertTrue(result.bytes() <= 100);
        assertTrue(((String) result.rows().get(0).get("B")).length() <= 99);
    }
}
