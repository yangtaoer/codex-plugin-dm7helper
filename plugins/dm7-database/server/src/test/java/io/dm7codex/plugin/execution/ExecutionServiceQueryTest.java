package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.List;
import javax.sql.rowset.serial.SerialClob;
import javax.sql.rowset.serial.SerialBlob;
import io.dm7codex.plugin.connection.DmConnectionFactory;
import io.dm7codex.plugin.connection.DriverIsolationFixture;
import org.junit.jupiter.api.Test;

class ExecutionServiceQueryTest {
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
        assertEquals(4099, result.bytes());
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
                new QueryCommand(UUID.randomUUID(), "SELECT C FROM T", 1, 5, 30));
        assertEquals("A😀", text.rows().get(0).get("C"));
        assertTrue(text.truncated());
        assertEquals(5, text.bytes());

        var binaryOpener = new TestJdbc.Opener().queryRows(
                List.of(List.of(new SerialBlob(new byte[100]))), List.of("B"));
        var binary = TestJdbc.service(binaryOpener).query(TestJdbc.session(),
                new QueryCommand(UUID.randomUUID(), "SELECT B FROM T", 1, 16, 30));
        assertTrue(((String) binary.rows().get(0).get("B")).startsWith("base64:"));
        assertTrue(binary.bytes() <= 16);
        assertTrue(binary.truncated());
    }
}
