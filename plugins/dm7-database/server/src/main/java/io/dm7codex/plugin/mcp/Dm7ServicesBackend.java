package io.dm7codex.plugin.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dm7codex.plugin.connection.*;
import io.dm7codex.plugin.execution.*;
import io.dm7codex.plugin.execution.ExecutionModels.*;
import io.dm7codex.plugin.release.ReleaseExportService;
import io.dm7codex.plugin.release.ReleaseLogService;
import io.dm7codex.plugin.runtime.*;
import io.dm7codex.plugin.sql.*;
import io.dm7codex.plugin.state.*;
import java.util.*;

/** Adapts the MCP contract to the existing application services without exposing secrets. */
public final class Dm7ServicesBackend implements Dm7McpServer.ToolBackend, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final StateDatabase database;
    private final SessionInitializer initializer;
    private final ConnectionConfigRepository profiles;
    private final ConnectionTestService connectionTests;
    private final ExecutionService executions;
    private final MetadataService metadata;
    private final ExecutionRepository history;
    private final ReleaseLogService releaseLog;
    private final ReleaseExportService exports;

    private Dm7ServicesBackend(StateDatabase database, SessionInitializer initializer,
            ConnectionConfigRepository profiles, ConnectionTestService connectionTests,
            ExecutionService executions, MetadataService metadata, ExecutionRepository history,
            ReleaseLogService releaseLog, ReleaseExportService exports) {
        this.database = database; this.initializer = initializer; this.profiles = profiles;
        this.connectionTests = connectionTests; this.executions = executions; this.metadata = metadata;
        this.history = history; this.releaseLog = releaseLog; this.exports = exports;
    }

    public static Dm7ServicesBackend open(RuntimePaths paths) throws Exception {
        Objects.requireNonNull(paths);
        StateDatabase state = StateDatabase.open(paths.stateDatabase());
        try {
            var sessions = new SessionRepository(state, paths.sessionsDirectory());
            var initializer = new SessionInitializer(paths, sessions);
            var vault = CredentialVault.open(paths.secretsDirectory());
            var profiles = ConnectionConfigRepository.open(paths.configDirectory(), vault);
            var factory = new DmConnectionFactory(profiles, vault, new DmDriverLoader(paths));
            var history = new ExecutionRepository(state);
            var registry = new ExecutionRegistry();
            var releaseLog = new ReleaseLogService(paths, sessions, java.time.Duration.ofSeconds(5));
            var executions = new ExecutionService(factory, new DmSqlParser(), new SqlSecurityPolicy(),
                    releaseLog, history, new ExecutionEventBus(2_000), registry);
            return new Dm7ServicesBackend(state, initializer, profiles,
                    new ConnectionTestService(factory, profiles), executions,
                    new MetadataService(factory), history, releaseLog,
                    new ReleaseExportService(paths, sessions, new ExportRepository(state)));
        } catch (Exception failure) {
            state.close();
            throw failure;
        }
    }

    public SessionState initialize(SessionIdentity identity) throws Exception {
        return initializer.initialize(identity);
    }

    @Override
    public Map<String, Object> call(String name, Map<String, Object> arguments, SessionState session) throws Exception {
        return switch (name) {
            case "dm7_list_connections" -> listConnections();
            case "dm7_test_connection" -> testConnection(arguments);
            case "dm7_query" -> query(arguments, session);
            case "dm7_execute" -> execute(arguments, session);
            case "dm7_describe_schema" -> describe(arguments);
            case "dm7_get_execution" -> getExecution(arguments, session);
            case "dm7_cancel_execution" -> cancel(arguments, session);
            case "dm7_get_release_log" -> convert(releaseLog.inspect(session));
            case "dm7_release_export" -> export(session);
            default -> throw new IllegalArgumentException("Unsupported tool");
        };
    }

    private Map<String, Object> listConnections() {
        var values = profiles.list().stream().map(profile -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", profile.id().toString());
            item.put("name", profile.name());
            item.put("isDefault", profile.isDefault());
            if (profile.schema() != null) item.put("schema", profile.schema());
            item.put("urlSummary", JdbcUrlDiagnostics.redact(profile.jdbcUrl()));
            return Collections.unmodifiableMap(item);
        }).toList();
        return Map.of("connections", values);
    }

    private Map<String, Object> testConnection(Map<String, Object> arguments) {
        var result = connectionTests.test(connectionId(arguments));
        if (!result.success()) throw new IllegalStateException("Connection test failed");
        return convert(result);
    }

    private Map<String, Object> query(Map<String, Object> arguments, SessionState session) {
        rejectParameters(arguments);
        var result = executions.query(session, new QueryCommand(connectionId(arguments), required(arguments, "sql"),
                integer(arguments, "maxRows", 1_000), longValue(arguments, "maxBytes", 10_485_760),
                integer(arguments, "timeoutSeconds", 60), ExecutionSource.MCP));
        if (!result.success()) throw new IllegalStateException("Query failed");
        return convert(result);
    }

    private Map<String, Object> execute(Map<String, Object> arguments, SessionState session) {
        rejectParameters(arguments);
        SqlPurpose purpose = SqlPurpose.valueOf(required(arguments, "purpose").toUpperCase(Locale.ROOT));
        var result = executions.execute(session, new ExecuteCommand(connectionId(arguments), required(arguments, "sql"),
                purpose, bool(arguments, "atomic", true), bool(arguments, "continueOnError", false),
                integer(arguments, "timeoutSeconds", 60), ExecutionSource.MCP));
        if (!result.success()) throw new IllegalStateException("Execution failed");
        return convert(result);
    }

    private Map<String, Object> describe(Map<String, Object> arguments) {
        var page = metadata.describe(connectionId(arguments), new MetadataService.MetadataRequest(
                optional(arguments, "schemaPattern"), optional(arguments, "objectPattern"),
                longValue(arguments, "offset", 0), integer(arguments, "limit", 50)));
        return convert(page);
    }

    private Map<String, Object> getExecution(Map<String, Object> arguments, SessionState session) throws Exception {
        String id = UUID.fromString(required(arguments, "executionId")).toString();
        var record = history.findExecution(id).orElseThrow(() -> new IllegalArgumentException("Execution was not found"));
        if (!record.sessionId().equals(session.sessionId())) throw new IllegalArgumentException("Execution was not found");
        var summary = new LinkedHashMap<String, Object>();
        summary.put("executionId", record.executionId()); summary.put("correlationId", record.correlationId());
        summary.put("connectionFingerprint", record.connectionFingerprint()); summary.put("source", record.source());
        summary.put("purpose", record.purpose()); summary.put("phase", record.phase()); summary.put("status", record.status());
        summary.put("startedAt", instant(record.startedAt())); summary.put("completedAt", instant(record.completedAt()));
        summary.put("affectedRowCount", record.affectedRowCount()); summary.put("returnedRowCount", record.returnedRowCount());
        summary.put("recorded", record.recorded()); summary.put("exclusionReason", record.exclusionReason());
        var statements = history.findStatements(id).stream().map(statement -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("index", statement.statementIndex()); item.put("kind", statement.statementKind());
            item.put("status", statement.status()); item.put("phase", statement.phase());
            item.put("rowCount", statement.rowCount()); item.put("recorded", statement.recorded());
            item.put("exclusionReason", statement.exclusionReason());
            return Collections.unmodifiableMap(item);
        }).toList();
        return Map.of("summary", summary, "statements", statements);
    }

    private Map<String, Object> cancel(Map<String, Object> arguments, SessionState session) throws Exception {
        UUID id = UUID.fromString(required(arguments, "executionId"));
        var record = history.findExecution(id.toString()).orElseThrow(() -> new IllegalArgumentException("Execution was not found"));
        if (!record.sessionId().equals(session.sessionId())) throw new IllegalArgumentException("Execution was not found");
        return Map.of("executionId", id.toString(), "cancelRequested", executions.cancel(id));
    }

    private Map<String, Object> export(SessionState session) throws Exception {
        var artifact = exports.export(session);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", artifact.id()); result.put("version", artifact.version());
        result.put("newActiveVersion", artifact.newActiveVersion());
        result.put("path", artifact.path().toAbsolutePath().normalize().toString());
        result.put("filename", artifact.filename()); result.put("byteLength", artifact.byteLength());
        result.put("sha256", artifact.sha256()); result.put("sealedSourceSha256", artifact.sealedSourceSha256());
        result.put("statementCount", artifact.statementCount()); result.put("firstSequence", artifact.firstSequence());
        result.put("lastSequence", artifact.lastSequence()); result.put("createdAt", artifact.createdAt().toString());
        return result;
    }

    private UUID connectionId(Map<String, Object> arguments) {
        String supplied = optional(arguments, "connectionId");
        if (supplied != null) {
            UUID id = UUID.fromString(supplied);
            if (profiles.find(id).isEmpty()) throw new IllegalArgumentException("Connection was not found");
            return id;
        }
        return profiles.list().stream().filter(ConnectionProfile::isDefault).map(ConnectionProfile::id)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No default connection is configured"));
    }

    private static void rejectParameters(Map<String, Object> arguments) {
        Object parameters = arguments.get("parameters");
        if (parameters instanceof Collection<?> values && !values.isEmpty()) {
            throw new IllegalArgumentException("Bound parameters are not supported by the execution service");
        }
    }

    private static String required(Map<String, Object> values, String key) {
        String result = optional(values, key);
        if (result == null) throw new IllegalArgumentException(key + " is required");
        return result;
    }
    private static String optional(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is invalid");
        return text;
    }
    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key); return value == null ? fallback : ((Number) value).intValue();
    }
    private static long longValue(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key); return value == null ? fallback : ((Number) value).longValue();
    }
    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key); return value == null ? fallback : (Boolean) value;
    }
    private static String instant(java.time.Instant value) { return value == null ? null : value.toString(); }
    private static Map<String, Object> convert(Object value) { return JSON.convertValue(value, MAP); }

    @Override public void close() {
        try { executions.close(); } finally { database.close(); }
    }
}
