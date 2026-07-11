package io.dm7codex.plugin.execution;

import io.dm7codex.plugin.sql.SqlKind;
import io.dm7codex.plugin.sql.SqlPurpose;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ExecutionModels {
    private ExecutionModels() {}

    public static final int MAX_ROWS = 10_000;
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    public static final int MAX_TIMEOUT_SECONDS = 3_600;

    public enum ExecutionStatus {
        QUEUED, CONNECTING, PARSING, EXECUTING, COMMITTING, LOGGING,
        COMPLETED, FAILED, CANCELLED, REJECTED
    }

    public enum ExecutionSource { MCP, CONSOLE }

    public record QueryCommand(UUID profileId, String sql, int maxRows, long maxBytes,
                               int timeoutSeconds, ExecutionSource source) {
        public QueryCommand(UUID profileId, String sql, int maxRows, long maxBytes, int timeoutSeconds) {
            this(profileId, sql, maxRows, maxBytes, timeoutSeconds, ExecutionSource.MCP);
        }
        public QueryCommand {
            Objects.requireNonNull(profileId, "profileId");
            sql = text(sql, "sql", 10_000_000);
            range(maxRows, 1, MAX_ROWS, "maxRows");
            if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("maxBytes is outside the allowed range");
            range(timeoutSeconds, 1, MAX_TIMEOUT_SECONDS, "timeoutSeconds");
            Objects.requireNonNull(source, "source");
        }
    }

    public record ExecuteCommand(UUID profileId, String script, SqlPurpose purpose, boolean atomic,
                                 boolean continueOnError, int timeoutSeconds, ExecutionSource source) {
        public ExecuteCommand(UUID profileId, String script, SqlPurpose purpose, boolean atomic,
                              boolean continueOnError, int timeoutSeconds) {
            this(profileId, script, purpose, atomic, continueOnError, timeoutSeconds, ExecutionSource.MCP);
        }
        public ExecuteCommand {
            Objects.requireNonNull(profileId, "profileId");
            script = text(script, "script", 10_000_000);
            Objects.requireNonNull(purpose, "purpose");
            if (atomic && continueOnError) throw new IllegalArgumentException("continueOnError requires atomic=false");
            range(timeoutSeconds, 1, MAX_TIMEOUT_SECONDS, "timeoutSeconds");
            Objects.requireNonNull(source, "source");
        }
    }

    public record ColumnValue(String label, int jdbcType, String typeName, Object value,
                              boolean truncated) {
        public ColumnValue {
            label = text(label, "label", 1024);
            typeName = typeName == null ? "" : typeName;
            if (value instanceof byte[] bytes) value = bytes.clone();
        }
        @Override public Object value() { return value instanceof byte[] bytes ? bytes.clone() : value; }
    }

    public record QueryColumn(String outputLabel, String originalLabel, String originalName,
                              int jdbcType, String typeName) {
        public QueryColumn {
            outputLabel = text(outputLabel, "outputLabel", 1024);
            originalLabel = text(originalLabel, "originalLabel", 1024);
            originalName = text(originalName, "originalName", 1024);
            typeName = typeName == null ? "" : typeName;
        }
    }

    public record QueryResult(UUID executionId, List<QueryColumn> columns, List<Map<String, Object>> rows,
                              boolean truncated, long returnedRows, long bytes, long elapsedMillis,
                              String databaseFingerprint, Optional<SafeError> error) {
        public QueryResult {
            Objects.requireNonNull(executionId, "executionId");
            columns = List.copyOf(columns);
            Objects.requireNonNull(rows, "rows");
            rows = rows.stream().map(row -> Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>(row))).toList();
            if (returnedRows < 0 || bytes < 0 || bytes > MAX_BYTES || elapsedMillis < 0)
                throw new IllegalArgumentException("invalid query result counts");
            if (returnedRows != rows.size()) throw new IllegalArgumentException("returnedRows must match rows");
            Objects.requireNonNull(databaseFingerprint, "databaseFingerprint");
            error = error == null ? Optional.empty() : error;
        }
        public boolean success() { return error.isEmpty(); }
    }

    public record StatementResult(int index, SqlKind kind, boolean success, boolean committed,
                                  long rowCount, boolean recorded, String exclusionReason,
                                  String commitBehavior, long elapsedMillis, Optional<SafeError> error) {
        public StatementResult {
            if (index < 0 || rowCount < 0 || elapsedMillis < 0) throw new IllegalArgumentException("negative statement result field");
            Objects.requireNonNull(kind, "kind");
            if (committed && !success) throw new IllegalArgumentException("committed statement must be successful");
            if (recorded && !committed) throw new IllegalArgumentException("recorded statement must be committed");
            commitBehavior = text(commitBehavior, "commitBehavior", 64);
            error = error == null ? Optional.empty() : error;
        }
    }

    public record ExecutionResult(UUID executionId, boolean success, ExecutionStatus status,
                                  List<StatementResult> statements, long elapsedMillis,
                                  String databaseFingerprint, Optional<SafeError> error) {
        public ExecutionResult {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(status, "status");
            statements = List.copyOf(statements);
            if (elapsedMillis < 0) throw new IllegalArgumentException("elapsedMillis must not be negative");
            Objects.requireNonNull(databaseFingerprint, "databaseFingerprint");
            error = error == null ? Optional.empty() : error;
            if (success && (status != ExecutionStatus.COMPLETED || error.isPresent()))
                throw new IllegalArgumentException("successful execution must be completed without error");
            if (!success && status == ExecutionStatus.COMPLETED)
                throw new IllegalArgumentException("failed execution cannot be completed");
        }
    }

    public record ExecutionEvent(long sequence, String sessionId, UUID executionId,
                                 ExecutionStatus status, Instant timestamp, String detail) {
        public ExecutionEvent {
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            sessionId = text(sessionId, "sessionId", 512);
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    public record ExecutionFilter(String sessionId, ExecutionStatus status, ExecutionSource source,
                                  SqlPurpose purpose, Instant startedAfter, Instant startedBefore) {
        public ExecutionFilter {
            if (sessionId != null) sessionId = text(sessionId, "sessionId", 512);
            if (startedAfter != null && startedBefore != null && startedAfter.isAfter(startedBefore))
                throw new IllegalArgumentException("startedAfter must not follow startedBefore");
        }
    }

    public record ExecutionSummary(UUID executionId, UUID correlationId, String sessionId,
                                   String connectionFingerprint, ExecutionSource source,
                                   Optional<SqlPurpose> purpose, ExecutionStatus status,
                                   Instant startedAt, Instant completedAt, long affectedRows,
                                   long returnedRows, boolean recorded, String exclusionReason) {
        public ExecutionSummary {
            Objects.requireNonNull(executionId); Objects.requireNonNull(correlationId);
            sessionId = text(sessionId, "sessionId", 512);
            Objects.requireNonNull(connectionFingerprint); Objects.requireNonNull(source);
            purpose = purpose == null ? Optional.empty() : purpose;
            Objects.requireNonNull(status); Objects.requireNonNull(startedAt);
            if (affectedRows < 0 || returnedRows < 0) throw new IllegalArgumentException("row counts must not be negative");
            if (isTerminal(status) && completedAt == null)
                throw new IllegalArgumentException("terminal summary requires completedAt");
        }
    }

    public record SafeError(UUID correlationId, ExecutionStatus phase, String message,
                            String sqlState, Integer errorCode, boolean restartRequired) {
        public SafeError {
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(phase, "phase");
            message = text(message, "message", 2048);
        }
    }

    public record Page<T>(List<T> items, int offset, int limit, boolean hasMore) {
        public Page {
            items = List.copyOf(items);
            if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid page");
            if (items.size() > limit) throw new IllegalArgumentException("page contains more than limit");
        }
    }

    static String text(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        String result = value.trim();
        if (result.isEmpty() || result.length() > max) throw new IllegalArgumentException(name + " is blank or too long");
        return result;
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " is outside the allowed range");
    }
    private static boolean isTerminal(ExecutionStatus status) {
        return status == ExecutionStatus.COMPLETED || status == ExecutionStatus.FAILED
                || status == ExecutionStatus.CANCELLED || status == ExecutionStatus.REJECTED;
    }
}
