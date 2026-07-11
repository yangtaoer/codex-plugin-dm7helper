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

class ExecutionServiceQueryTest {
    @TempDir Path tempDir;

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
