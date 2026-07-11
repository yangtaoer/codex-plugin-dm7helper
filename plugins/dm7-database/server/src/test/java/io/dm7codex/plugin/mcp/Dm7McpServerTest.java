package io.dm7codex.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionState;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class Dm7McpServerTest {
    private static final List<String> TOOL_NAMES = List.of(
            "dm7_open_console", "dm7_list_connections", "dm7_test_connection",
            "dm7_query", "dm7_execute", "dm7_describe_schema", "dm7_get_execution",
            "dm7_cancel_execution", "dm7_get_release_log", "dm7_release_export");

    @Test
    void toolsExposeExactSchemasAndSafetyAnnotations() {
        var server = server(new ArrayList<>());
        var tools = server.toolDefinitions();

        assertEquals(TOOL_NAMES, List.copyOf(tools.keySet()));
        assertEquals(10, tools.size());
        tools.values().forEach(tool -> {
            assertEquals("https://json-schema.org/draft/2020-12/schema", tool.inputSchema().get("$schema"));
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            assertNotNull(tool.description());
            assertFalse(tool.description().isBlank());
        });
        assertTrue(tools.get("dm7_query").annotations().readOnlyHint());
        assertTrue(tools.get("dm7_list_connections").annotations().readOnlyHint());
        assertTrue(tools.get("dm7_describe_schema").annotations().readOnlyHint());
        assertTrue(tools.get("dm7_get_execution").annotations().readOnlyHint());
        assertTrue(tools.get("dm7_get_release_log").annotations().readOnlyHint());
        assertFalse(tools.get("dm7_open_console").annotations().readOnlyHint());
        assertFalse(tools.get("dm7_open_console").annotations().destructiveHint());
        assertTrue(tools.get("dm7_execute").annotations().destructiveHint());
        assertTrue(tools.get("dm7_cancel_execution").annotations().destructiveHint());
        assertTrue(tools.get("dm7_release_export").annotations().destructiveHint());
        assertTrue(tools.get("dm7_test_connection").annotations().openWorldHint());

        @SuppressWarnings("unchecked")
        var executeRequired = (List<String>) tools.get("dm7_execute").inputSchema().get("required");
        assertTrue(executeRequired.containsAll(List.of("sql", "purpose")));
        @SuppressWarnings("unchecked")
        var executeProperties = (Map<String, Object>) tools.get("dm7_execute").inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var purpose = (Map<String, Object>) executeProperties.get("purpose");
        assertEquals(List.of("production_change", "migration", "test", "mock", "seed", "sample"), purpose.get("enum"));
        @SuppressWarnings("unchecked")
        var exportRequired = (List<String>) tools.get("dm7_release_export").inputSchema().get("required");
        assertEquals(List.of("confirm"), exportRequired);
    }

    @ParameterizedTest(name = "{0} initializes the resolved session first")
    @MethodSource("toolNames")
    void everyToolInitializesTheResolvedSessionBeforeAnyValidationOrBusinessAction(String tool) {
        var events = new ArrayList<String>();
        var server = server(events);

        server.call(tool, Map.of("sessionId", "forged-by-tool"));
        assertFalse(events.isEmpty(), tool);
        assertEquals("initialize:trusted-thread", events.get(0), tool);
    }

    static java.util.stream.Stream<String> toolNames() { return TOOL_NAMES.stream(); }

    @Test
    void expectedValidationFailuresAreSafeToolErrorsWithStructuredContent() {
        var result = server(new ArrayList<>()).call("dm7_release_export", Map.of("confirm", false));

        assertEquals(true, result.isError());
        assertInstanceOf(Map.class, result.structuredContent());
        @SuppressWarnings("unchecked") var structured = (Map<String, Object>) result.structuredContent();
        assertEquals("CONFIRMATION_REQUIRED", structured.get("code"));
        assertNotNull(structured.get("correlationId"));
        assertFalse(result.content().isEmpty());
        String rendered = result.toString().toLowerCase();
        assertFalse(rendered.contains("password"));
        assertFalse(rendered.contains("master.key"));
        assertFalse(rendered.contains("secret-sentinel"));
    }

    @Test
    void openConsoleReportsUnavailableUntilTaskEightBackendIsInjected() {
        CallToolResult result = server(new ArrayList<>()).call("dm7_open_console", Map.of());
        assertEquals(true, result.isError());
        @SuppressWarnings("unchecked") var structured = (Map<String, Object>) result.structuredContent();
        assertEquals("CONSOLE_NOT_AVAILABLE", structured.get("code"));
    }

    private static Dm7McpServer server(List<String> events) {
        SessionIdentity identity = new SessionIdentity("trusted-thread", "codex_thread", "verified");
        SessionState state = new SessionState("session-1", "hash", 1, "unbound",
                Path.of("active.sql").toAbsolutePath(), Instant.EPOCH);
        Dm7McpServer.SessionStarter starter = supplied -> {
            events.add("initialize:" + supplied.externalId());
            return state;
        };
        Dm7McpServer.ToolBackend backend = (name, arguments, session) -> {
            events.add("business:" + name);
            return Map.of("tool", name, "ok", true);
        };
        return new Dm7McpServer(() -> identity, starter, backend, Dm7McpServer.ConsoleLauncher.unavailable());
    }
}
