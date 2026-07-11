package io.dm7codex.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.UUID;
import io.dm7codex.plugin.execution.ExecutionModels.ExecutionEvent;
import io.dm7codex.plugin.execution.ExecutionModels.ExecutionStatus;
import java.math.BigInteger;
import java.math.BigDecimal;

class Dm7ServicesBackendTest {
    @TempDir Path temporary;

    @Test void parsesTypedMcpParametersWithoutGuessingOrLeakingUnsupportedValues() {
        var nullParameter = new java.util.LinkedHashMap<String, Object>();
        nullParameter.put("jdbcType", Types.VARCHAR); nullParameter.put("value", null);
        var parsed = Dm7ServicesBackend.parameters(Map.of("parameters", java.util.List.of(
                Map.of("jdbcType", Types.NVARCHAR, "value", "中文"),
                nullParameter,
                Map.of("jdbcType", Types.INTEGER, "value", 42),
                Map.of("jdbcType", Types.BOOLEAN, "value", true),
                Map.of("jdbcType", Types.DATE, "value", "2026-07-11"),
                Map.of("jdbcType", Types.TIMESTAMP, "value", "2026-07-11T12:30:00"),
                Map.of("jdbcType", Types.VARBINARY, "value", "Af8="))));

        assertEquals("中文", parsed.get(0).value());
        assertNull(parsed.get(1).value());
        assertEquals(42, parsed.get(2).value());
        assertEquals(true, parsed.get(3).value());
        assertEquals(LocalDate.of(2026, 7, 11), parsed.get(4).value());
        assertEquals(LocalDateTime.of(2026, 7, 11, 12, 30), parsed.get(5).value());
        assertArrayEquals(new byte[]{1, (byte) 0xff}, (byte[]) parsed.get(6).value());
        assertThrows(IllegalArgumentException.class, () -> Dm7ServicesBackend.parameters(Map.of(
                "parameters", java.util.List.of(Map.of("jdbcType", Types.JAVA_OBJECT, "value", "secret")))));
    }

    @Test void executionEventHistoryIsFilteredAndStructuredForGetExecution() {
        UUID wanted = UUID.randomUUID();
        var events = Dm7ServicesBackend.executionEvents(java.util.List.of(
                new ExecutionEvent(1, "session", wanted, ExecutionStatus.QUEUED, Instant.EPOCH, null),
                new ExecutionEvent(2, "session", UUID.randomUUID(), ExecutionStatus.EXECUTING, Instant.EPOCH, null),
                new ExecutionEvent(3, "session", wanted, ExecutionStatus.COMPLETED, Instant.EPOCH.plusSeconds(1), "done")), wanted);
        assertEquals(2, events.size());
        assertEquals("QUEUED", events.get(0).get("status"));
        assertEquals("COMPLETED", events.get(1).get("status"));
        assertEquals("1970-01-01T00:00:01Z", events.get(1).get("timestamp"));
    }

    @Test void parsesIntegralParametersExactlyAtEveryJdbcBoundary() {
        assertEquals((byte) -128, parameter(Types.TINYINT, -128));
        assertEquals((byte) 127, parameter(Types.TINYINT, 127));
        assertEquals((short) -32768, parameter(Types.SMALLINT, -32768));
        assertEquals((short) 32767, parameter(Types.SMALLINT, 32767));
        assertEquals(Integer.MIN_VALUE, parameter(Types.INTEGER, Integer.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE, parameter(Types.INTEGER, Integer.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, parameter(Types.BIGINT, BigInteger.valueOf(Long.MIN_VALUE)));
        assertEquals(Long.MAX_VALUE, parameter(Types.BIGINT, BigInteger.valueOf(Long.MAX_VALUE)));

        for (int type : java.util.List.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT)) {
            assertThrows(IllegalArgumentException.class, () -> parameter(type, 1.75), "fraction " + type);
            assertThrows(IllegalArgumentException.class, () -> parameter(type, "1"), "string " + type);
        }
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.TINYINT, 300));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.TINYINT, -129));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.BIGINT,
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.BIGINT,
                BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)));
    }

    @Test void rejectsNonFiniteOrOutOfRangeFloatingAndDecimalParameters() {
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.REAL, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.FLOAT, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.DOUBLE, Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.REAL, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.DOUBLE, new BigDecimal("1e-9999")));
        assertThrows(IllegalArgumentException.class, () -> parameter(Types.DECIMAL, "NaN"));
        assertEquals(Float.MAX_VALUE, parameter(Types.REAL, Float.MAX_VALUE));
        assertEquals(Double.MAX_VALUE, parameter(Types.DOUBLE, Double.MAX_VALUE));
        assertEquals(new BigDecimal("123.45"), parameter(Types.DECIMAL, new BigDecimal("123.45")));
    }

    private static Object parameter(int jdbcType, Object value) {
        return Dm7ServicesBackend.parameters(Map.of("parameters", java.util.List.of(
                Map.of("jdbcType", jdbcType, "value", value)))).get(0).value();
    }

    @Test
    void emptyRuntimeUsesRealReleaseServiceAndReturnsSafeConnectionError() throws Exception {
        try (var backend = Dm7ServicesBackend.open(RuntimePaths.forTest(temporary))) {
            var server = new Dm7McpServer(
                    () -> new SessionIdentity("thread-a", "codex_thread", "verified"),
                    backend::initialize, backend, Dm7McpServer.ConsoleLauncher.unavailable());

            var listed = server.call("dm7_list_connections", Map.of());
            assertEquals(false, listed.isError());
            assertEquals(Map.of("connections", java.util.List.of()), listed.structuredContent());

            var release = server.call("dm7_get_release_log", Map.of());
            assertEquals(false, release.isError());
            @SuppressWarnings("unchecked") var releaseData = (Map<String, Object>) release.structuredContent();
            assertEquals("v001", releaseData.get("currentVersion"));

            var exported = server.call("dm7_release_export", Map.of("confirm", true));
            assertEquals(false, exported.isError(), exported.toString());
            @SuppressWarnings("unchecked") var exportData = (Map<String, Object>) exported.structuredContent();
            assertEquals("v001", exportData.get("version"));
            assertEquals("v002", exportData.get("newActiveVersion"));
            Path exportedPath = Path.of((String) exportData.get("path")).toAbsolutePath().normalize();
            assertTrue(exportedPath.startsWith(temporary.resolve("exports").toAbsolutePath().normalize()));
            assertTrue(Files.isRegularFile(exportedPath));

            var query = server.call("dm7_query", Map.of("sql", "select 1"));
            assertEquals(true, query.isError());
            assertFalse(query.toString().contains(temporary.toAbsolutePath().toString()));
            assertFalse(query.toString().toLowerCase().contains("jdbc:"));
        }
    }

    @Test
    void twoResolvedThreadIdentitiesCreateIndependentBomFreeActiveLogs() throws Exception {
        try (var backend = Dm7ServicesBackend.open(RuntimePaths.forTest(temporary))) {
            var first = backend.initialize(io.dm7codex.plugin.runtime.SessionIdentityResolver.resolve(
                    Map.of("CODEX_THREAD_ID", "thread-one")));
            var second = backend.initialize(io.dm7codex.plugin.runtime.SessionIdentityResolver.resolve(
                    Map.of("CODEX_THREAD_ID", "thread-two")));

            assertNotEquals(first.sessionId(), second.sessionId());
            assertNotEquals(first.activeSql().getParent(), second.activeSql().getParent());
            for (var state : java.util.List.of(first, second)) {
                byte[] bytes = Files.readAllBytes(state.activeSql());
                assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf);
                assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("version: v001"));
            }
        }
    }

    @Test void consoleConnectionCrudNeverReturnsPasswordAndExportDownloadIsSessionScoped() throws Exception {
        Path driver=temporary.resolve("local-driver.jar");Files.writeString(driver,"not-a-real-driver",StandardCharsets.UTF_8);
        try(var backend=Dm7ServicesBackend.open(RuntimePaths.forTest(temporary))){
            var session=backend.initialize(new SessionIdentity("console-thread","test","verified"));
            var created=backend.call("connections.create",Map.of(
                    "name","达梦中文连接","driverJar",driver.toString(),"jdbcUrl","jdbc:dm7://localhost:5236?password=hidden",
                    "username","operator","password","top-secret"),session);
            assertEquals("达梦中文连接",created.get("name"));
            assertFalse(created.toString().contains("top-secret"));assertTrue(created.get("jdbcUrl").toString().contains("***"));
            assertFalse(created.toString().contains(driver.toString()));
            assertEquals(driver.getFileName().toString(),created.get("driverFileName"));
            assertEquals(true,created.get("configured"));
            String id=(String)created.get("id");
            assertEquals(id,backend.call("connections.get",Map.of("id",id),session).get("id"));
            assertFalse(backend.call("connections.list",Map.of(),session).toString().contains("top-secret"));
            try(var console=new io.dm7codex.plugin.http.ConsoleHttpServer(
                    new io.dm7codex.plugin.http.ConsoleTokenService(),backend,backend.eventBus())){
                var opened=console.open(session);assertEquals("v001",opened.get("currentVersion"));
                @SuppressWarnings("unchecked") var summary=(Map<String,Object>)opened.get("connection");
                assertEquals(id,summary.get("id"));assertEquals("达梦中文连接",summary.get("name"));
                assertEquals(true,summary.get("configured"));assertFalse(opened.toString().contains(driver.toString()));
                assertFalse(opened.toString().contains("top-secret"));
            }
            var exported=backend.call("release.export",Map.of("confirm",true),session);
            assertFalse(exported.containsKey("path"));String exportId=(String)exported.get("id");
            try(var download=backend.download(exportId,session).orElseThrow()){
                assertEquals("application/sql; charset=utf-8",download.contentType());
                var bytes=new java.io.ByteArrayOutputStream();download.writeTo(bytes);
                assertFalse(bytes.toString(StandardCharsets.UTF_8).startsWith("\uFEFF"));
            }
            var other=backend.initialize(new SessionIdentity("other-thread","test","verified"));
            assertTrue(backend.download(exportId,other).isEmpty());
            backend.call("connections.delete",Map.of("id",id),session);
            assertEquals(Map.of("connections",java.util.List.of()),backend.call("connections.list",Map.of(),session));
        }
    }

    @Test void consoleConnectionPasswordContractPreservesReplacesAndClearsWithoutDisclosure() throws Exception {
        Path driver=temporary.resolve("credential-contract.jar");Files.writeString(driver,"fixture",StandardCharsets.UTF_8);
        try(var backend=Dm7ServicesBackend.open(RuntimePaths.forTest(temporary))){
            var session=backend.initialize(new SessionIdentity("password-contract","test","verified"));
            var created=backend.call("connections.create",Map.of("name","凭据契约","driverJar",driver.toString(),
                    "jdbcUrl","jdbc:dm7://localhost:5236","username","operator","password","first-value"),session);
            assertEquals(true,created.get("hasPassword"));
            String id=(String)created.get("id");
            var preserved=backend.call("connections.update",Map.of("id",id,"name","凭据契约-更新"),session);
            assertEquals(true,preserved.get("hasPassword"));
            var blank=backend.call("connections.update",Map.of("id",id,"password","   "),session);
            assertEquals(true,blank.get("hasPassword"));
            var cleared=backend.call("connections.update",Map.of("id",id,"clearPassword",true),session);
            assertEquals(false,cleared.get("hasPassword"));
            assertThrows(IllegalArgumentException.class,()->backend.call("connections.update",
                    Map.of("id",id,"password","replacement","clearPassword",true),session));
            assertThrows(IllegalArgumentException.class,()->backend.call("connections.create",Map.of("name","无意义清除",
                    "driverJar",driver.toString(),"jdbcUrl","jdbc:dm7://localhost:5236","username","operator","clearPassword",true),session));
            String output=backend.call("connections.list",Map.of(),session).toString();
            assertFalse(output.contains("first-value"));assertFalse(output.contains("replacement"));
        }
    }
}
