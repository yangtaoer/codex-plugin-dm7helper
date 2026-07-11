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
        var result = executions.query(session, new QueryCommand(connectionId(arguments), executionId(arguments),
                required(arguments, "sql"), parameters(arguments), integer(arguments, "maxRows", 1_000),
                longValue(arguments, "maxBytes", 10_485_760), integer(arguments, "timeoutSeconds", 60),
                ExecutionSource.MCP));
        return queryResult(result);
    }

    private Map<String, Object> execute(Map<String, Object> arguments, SessionState session) {
        SqlPurpose purpose = SqlPurpose.valueOf(required(arguments, "purpose").toUpperCase(Locale.ROOT));
        var result = executions.execute(session, new ExecuteCommand(connectionId(arguments), executionId(arguments),
                required(arguments, "sql"), parameters(arguments), purpose, bool(arguments, "atomic", true),
                bool(arguments, "continueOnError", false), integer(arguments, "timeoutSeconds", 60), ExecutionSource.MCP));
        return executionResult(result);
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
        return Map.of("summary", summary, "statements", statements,
                "events", executionEvents(executions.events(session.sessionId(), 0), UUID.fromString(id)));
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

    private static Map<String, Object> queryResult(QueryResult value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("executionId", value.executionId().toString()); result.put("success", value.success());
        result.put("columns", value.columns().stream().map(Dm7ServicesBackend::convert).toList());
        result.put("rows", value.rows()); result.put("truncated", value.truncated());
        result.put("returnedRows", value.returnedRows()); result.put("bytes", value.bytes());
        result.put("elapsedMillis", value.elapsedMillis()); result.put("databaseFingerprint", value.databaseFingerprint());
        result.put("error", value.error().map(Dm7ServicesBackend::safeError).orElse(null));
        return result;
    }

    private static Map<String, Object> executionResult(ExecutionResult value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("executionId", value.executionId().toString()); result.put("success", value.success());
        result.put("status", value.status().name());
        result.put("statements", value.statements().stream().map(Dm7ServicesBackend::statementResult).toList());
        result.put("elapsedMillis", value.elapsedMillis()); result.put("databaseFingerprint", value.databaseFingerprint());
        result.put("error", value.error().map(Dm7ServicesBackend::safeError).orElse(null));
        return result;
    }

    private static Map<String, Object> statementResult(StatementResult value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("index", value.index()); result.put("kind", value.kind().name());
        result.put("success", value.success()); result.put("committed", value.committed());
        result.put("rowCount", value.rowCount()); result.put("recorded", value.recorded());
        result.put("exclusionReason", value.exclusionReason()); result.put("commitBehavior", value.commitBehavior());
        result.put("elapsedMillis", value.elapsedMillis());
        result.put("error", value.error().map(Dm7ServicesBackend::safeError).orElse(null));
        return result;
    }

    private static Map<String, Object> safeError(SafeError value) {
        var error = new LinkedHashMap<String, Object>();
        error.put("correlationId", value.correlationId().toString()); error.put("phase", value.phase().name());
        error.put("message", value.message()); error.put("sqlState", value.sqlState());
        error.put("errorCode", value.errorCode()); error.put("restartRequired", value.restartRequired());
        return error;
    }

    static List<Map<String, Object>> executionEvents(List<ExecutionEvent> events, UUID executionId) {
        return events.stream().filter(event -> event.executionId().equals(executionId)).map(event -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("sequence", event.sequence()); item.put("status", event.status().name());
            item.put("timestamp", event.timestamp().toString()); item.put("detail", event.detail());
            return Collections.unmodifiableMap(item);
        }).toList();
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

    private static UUID executionId(Map<String, Object> arguments) {
        String value = optional(arguments, "executionId");
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }

    static List<SqlParameter> parameters(Map<String, ?> arguments) {
        Object raw = arguments.get("parameters");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("parameters must be an array");
        var result = new ArrayList<SqlParameter>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item) || !(item.get("jdbcType") instanceof Number type)) {
                throw new IllegalArgumentException("parameter is invalid");
            }
            int jdbcType = type.intValue();
            if (!item.containsKey("value")) throw new IllegalArgumentException("parameter value is required");
            result.add(new SqlParameter(parameterValue(item.get("value"), jdbcType), jdbcType));
        }
        return List.copyOf(result);
    }

    private static Object parameterValue(Object value, int type) {
        if (value == null) return null;
        return switch (type) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR,
                    java.sql.Types.CLOB, java.sql.Types.NCHAR, java.sql.Types.NVARCHAR,
                    java.sql.Types.LONGNVARCHAR, java.sql.Types.NCLOB -> requireValue(value, String.class);
            case java.sql.Types.TINYINT -> ((Number) requireValue(value, Number.class)).byteValue();
            case java.sql.Types.SMALLINT -> ((Number) requireValue(value, Number.class)).shortValue();
            case java.sql.Types.INTEGER -> ((Number) requireValue(value, Number.class)).intValue();
            case java.sql.Types.BIGINT -> ((Number) requireValue(value, Number.class)).longValue();
            case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> new java.math.BigDecimal(value.toString());
            case java.sql.Types.REAL -> ((Number) requireValue(value, Number.class)).floatValue();
            case java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> ((Number) requireValue(value, Number.class)).doubleValue();
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> requireValue(value, Boolean.class);
            case java.sql.Types.DATE -> java.time.LocalDate.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIME -> java.time.LocalTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIME_WITH_TIMEZONE -> java.time.OffsetTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIMESTAMP -> java.time.LocalDateTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> java.time.OffsetDateTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY,
                    java.sql.Types.BLOB -> Base64.getDecoder().decode((String) requireValue(value, String.class));
            default -> throw new IllegalArgumentException("JDBC parameter type is not supported");
        };
    }

    private static Object requireValue(Object value, Class<?> type) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("JDBC parameter value has the wrong type");
        return value;
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
