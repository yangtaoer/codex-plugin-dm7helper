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
}
