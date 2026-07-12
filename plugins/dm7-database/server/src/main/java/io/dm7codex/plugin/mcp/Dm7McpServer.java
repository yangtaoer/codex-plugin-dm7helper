package io.dm7codex.plugin.mcp;

import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionState;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Dm7McpServer {
    private final MetadataIdentitySupplier identities;
    private final SessionStarter sessions;
    private final ToolBackend backend;
    private final ConsoleLauncher console;
    private final Map<String, Tool> definitions;

    public Dm7McpServer(IdentitySupplier identities, SessionStarter sessions,
                        ToolBackend backend, ConsoleLauncher console) {
        this(ignored -> identities.resolve(), sessions, backend, console);
    }

    public Dm7McpServer(MetadataIdentitySupplier identities, SessionStarter sessions,
                        ToolBackend backend, ConsoleLauncher console) {
        this.identities = Objects.requireNonNull(identities);
        this.sessions = Objects.requireNonNull(sessions);
        this.backend = Objects.requireNonNull(backend);
        this.console = Objects.requireNonNull(console);
        this.definitions = Dm7ToolSchemas.definitions();
    }

    public Map<String, Tool> toolDefinitions() { return definitions; }

    public List<SyncToolSpecification> toolSpecifications() {
        var specifications = new ArrayList<SyncToolSpecification>();
        definitions.values().forEach(tool -> specifications.add(SyncToolSpecification.builder()
                .tool(tool).callHandler((exchange, request) ->
                        call(tool.name(), request.arguments(), request.meta())).build()));
        return List.copyOf(specifications);
    }

    public CallToolResult call(String name, Map<String, Object> arguments) {
        return call(name, arguments, Map.of());
    }

    public CallToolResult call(String name, Map<String, Object> arguments, Map<String, Object> requestMeta) {
        String correlationId = UUID.randomUUID().toString();
        try {
            // Deliberately the first business action for every tool handler.
            SessionIdentity identity = identities.resolve(requestMeta == null ? Map.of() : requestMeta);
            SessionState session = sessions.initialize(identity);
            Map<String, Object> safeArguments = arguments == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            if (!definitions.containsKey(name)) return error("UNKNOWN_TOOL", "未知工具。", correlationId);
            var validation = McpJsonDefaults.getSchemaValidator().validate(
                    definitions.get(name).inputSchema(), safeArguments);
            if (!validation.valid()) {
                return error("INVALID_ARGUMENT", "工具参数不符合输入约束。", correlationId);
            }
            if (name.equals("dm7_open_console")) return success(console.open(session), "控制台已准备。", false);
            if (name.equals("dm7_release_export") && !Boolean.TRUE.equals(safeArguments.get("confirm"))) {
                return error("CONFIRMATION_REQUIRED", "发版导出要求 confirm=true。", correlationId);
            }
            Map<String, Object> output = backend.call(name, safeArguments, session);
            if (name.equals("dm7_get_release_log")) {
                var enriched = new LinkedHashMap<String, Object>(output);
                enriched.put("sessionIdentitySource", identity.source());
                enriched.put("sessionIsolation", identity.isolation());
                enriched.put("mcpProcessId", ProcessHandle.current().pid());
                output = enriched;
            }
            boolean failed = Boolean.FALSE.equals(output.get("success")) || Boolean.FALSE.equals(output.get("ok"));
            return success(output, summary(name, output), failed);
        } catch (ConsoleUnavailable unavailable) {
            return error("CONSOLE_NOT_AVAILABLE", "控制台后端将在后续任务中提供。", correlationId);
        } catch (UnsafeNumericInputException unsafeNumeric) {
            return error("INVALID_ARGUMENT", "数值参数无法安全表示。", correlationId,
                    "UNSAFE_NUMERIC_INPUT");
        } catch (IllegalArgumentException invalid) {
            return error("INVALID_ARGUMENT", "工具参数无效。", correlationId);
        } catch (Exception failure) {
            return error("OPERATION_FAILED", "DM7 操作失败；请使用关联 ID 排查。", correlationId);
        }
    }

    private static CallToolResult success(Map<String, Object> structured, String text, boolean isError) {
        return CallToolResult.builder().structuredContent(Collections.unmodifiableMap(new LinkedHashMap<>(structured)))
                .addTextContent(text).isError(isError).build();
    }

    private static CallToolResult error(String code, String message, String correlationId) {
        return error(code, message, correlationId, null);
    }

    private static CallToolResult error(String code, String message, String correlationId, String reason) {
        var safe = new LinkedHashMap<String, Object>();
        safe.put("ok", false); safe.put("code", code); safe.put("message", message);
        safe.put("correlationId", correlationId);
        if (reason != null) safe.put("reason", reason);
        return success(safe, message + " 关联 ID：" + correlationId, true);
    }

    private static String summary(String name, Map<String, Object> output) {
        return name + " 已完成。";
    }

    @FunctionalInterface public interface IdentitySupplier { SessionIdentity resolve(); }
    @FunctionalInterface public interface MetadataIdentitySupplier {
        SessionIdentity resolve(Map<String, Object> requestMeta);
    }
    @FunctionalInterface public interface SessionStarter { SessionState initialize(SessionIdentity identity) throws Exception; }
    @FunctionalInterface public interface ToolBackend {
        Map<String, Object> call(String name, Map<String, Object> arguments, SessionState session) throws Exception;
    }
    @FunctionalInterface public interface ConsoleLauncher {
        Map<String, Object> open(SessionState session) throws Exception;
        static ConsoleLauncher unavailable() { return ignored -> { throw new ConsoleUnavailable(); }; }
    }
    private static final class ConsoleUnavailable extends Exception {}
}
