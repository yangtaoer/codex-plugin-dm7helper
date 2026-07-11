package io.dm7codex.plugin.state;

import static io.dm7codex.plugin.execution.ExecutionModels.*;

import io.dm7codex.plugin.sql.SqlPurpose;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ExecutionRepository {
    private final StateDatabase database;

    public ExecutionRepository(StateDatabase database) {
        this.database = database;
    }

    public void saveExecution(ExecutionRecord execution) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO execution(
                            execution_id, correlation_id, session_id, connection_fingerprint,
                            source, purpose, sql_text, phase, status, started_at, completed_at,
                            affected_row_count, returned_row_count, sql_state, error_code,
                            error_message, recorded, exclusion_reason
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, execution.executionId());
            statement.setString(2, execution.correlationId());
            statement.setString(3, execution.sessionId());
            statement.setString(4, execution.connectionFingerprint());
            statement.setString(5, execution.source());
            statement.setString(6, execution.purpose());
            statement.setString(7, execution.sqlText());
            statement.setString(8, execution.phase());
            statement.setString(9, execution.status());
            statement.setString(10, execution.startedAt().toString());
            statement.setString(11, instantText(execution.completedAt()));
            setNullableLong(statement, 12, execution.affectedRowCount());
            setNullableLong(statement, 13, execution.returnedRowCount());
            statement.setString(14, execution.sqlState());
            setNullableInteger(statement, 15, execution.errorCode());
            statement.setString(16, execution.errorMessage());
            statement.setInt(17, execution.recorded() ? 1 : 0);
            statement.setString(18, execution.exclusionReason());
            statement.executeUpdate();
        }
    }

    public Optional<ExecutionRecord> findExecution(String executionId) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement(
                        "SELECT * FROM execution WHERE execution_id = ?")) {
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readExecution(rows)) : Optional.empty();
            }
        }
    }

    public void started(UUID executionId, String sessionId, String connectionFingerprint,
                        ExecutionSource source, Optional<SqlPurpose> purpose, String sql) throws SQLException {
        var now = Instant.now();
        saveExecution(new ExecutionRecord(executionId.toString(), UUID.randomUUID().toString(),
                sessionId, connectionFingerprint, source.name(),
                purpose.map(Enum::name).orElse(null), sql, ExecutionStatus.CONNECTING.name(),
                "RUNNING", now, null, null, null, null, null, null, false, null));
    }

    public void progress(UUID executionId, ExecutionStatus phase) throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                     "UPDATE execution SET phase = ? WHERE execution_id = ? AND status = 'RUNNING'")) {
            statement.setString(1, phase.name());
            statement.setString(2, executionId.toString());
            statement.executeUpdate();
        }
    }

    public void statementFinished(UUID executionId, StatementResult result) throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                     UPDATE execution SET affected_row_count = COALESCE(affected_row_count, 0) + ?,
                         recorded = CASE WHEN recorded = 1 OR ? = 1 THEN 1 ELSE 0 END,
                         exclusion_reason = COALESCE(?, exclusion_reason)
                     WHERE execution_id = ?
                     """)) {
            statement.setLong(1, result.rowCount());
            statement.setInt(2, result.recorded() ? 1 : 0);
            statement.setString(3, result.exclusionReason());
            statement.setString(4, executionId.toString());
            statement.executeUpdate();
        }
    }

    public void terminal(UUID executionId, ExecutionStatus status, Optional<SafeError> error)
            throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                     UPDATE execution SET phase = ?, status = ?, completed_at = ?,
                         sql_state = ?, error_code = ?, error_message = ?
                     WHERE execution_id = ?
                     """)) {
            statement.setString(1, status.name());
            statement.setString(2, status.name());
            statement.setString(3, Instant.now().toString());
            statement.setString(4, error.map(SafeError::sqlState).orElse(null));
            setNullableInteger(statement, 5, error.map(SafeError::errorCode).orElse(null));
            statement.setString(6, error.map(SafeError::message).orElse(null));
            statement.setString(7, executionId.toString());
            statement.executeUpdate();
        }
    }

    public Page<ExecutionSummary> search(ExecutionFilter filter, int offset, int limit) throws SQLException {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid history page");
        filter = filter == null ? new ExecutionFilter(null, null, null, null, null, null) : filter;
        var sql = new StringBuilder("SELECT * FROM execution WHERE 1=1");
        var values = new ArrayList<Object>();
        if (filter.sessionId() != null) { sql.append(" AND session_id = ?"); values.add(filter.sessionId()); }
        if (filter.status() != null) { sql.append(" AND status = ?"); values.add(filter.status().name()); }
        if (filter.source() != null) { sql.append(" AND source = ?"); values.add(filter.source().name()); }
        if (filter.purpose() != null) { sql.append(" AND purpose = ?"); values.add(filter.purpose().name()); }
        if (filter.startedAfter() != null) { sql.append(" AND started_at >= ?"); values.add(filter.startedAfter().toString()); }
        if (filter.startedBefore() != null) { sql.append(" AND started_at <= ?"); values.add(filter.startedBefore().toString()); }
        sql.append(" ORDER BY started_at DESC, execution_id LIMIT ? OFFSET ?");
        values.add(limit + 1); values.add(offset);
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
            try (var rows = statement.executeQuery()) {
                var items = new ArrayList<ExecutionSummary>();
                while (rows.next() && items.size() <= limit) items.add(summary(rows));
                boolean more = items.size() > limit;
                if (more) items.remove(items.size() - 1);
                return new Page<>(items, offset, limit, more);
            }
        }
    }

    private static ExecutionSummary summary(ResultSet rows) throws SQLException {
        String purpose = rows.getString("purpose");
        String completed = rows.getString("completed_at");
        return new ExecutionSummary(UUID.fromString(rows.getString("execution_id")),
                UUID.fromString(rows.getString("correlation_id")), rows.getString("session_id"),
                rows.getString("connection_fingerprint"), ExecutionSource.valueOf(rows.getString("source")),
                purpose == null ? Optional.empty() : Optional.of(SqlPurpose.valueOf(purpose)),
                ExecutionStatus.valueOf(rows.getString("status")),
                Instant.parse(rows.getString("started_at")), completed == null ? null : Instant.parse(completed),
                Math.max(0, rows.getLong("affected_row_count")),
                Math.max(0, rows.getLong("returned_row_count")), rows.getInt("recorded") != 0,
                rows.getString("exclusion_reason"));
    }

    public void appendStatement(StatementEventRecord event) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO statement_event(
                            execution_id, session_id, release_version, statement_index,
                            sequence_number, statement_kind, status, phase, row_count,
                            sql_state, error_code, recorded, exclusion_reason, raw_sql,
                            replayable_sql, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, event.executionId());
            statement.setString(2, event.sessionId());
            statement.setInt(3, event.releaseVersion());
            statement.setInt(4, event.statementIndex());
            setNullableLong(statement, 5, event.sequenceNumber());
            statement.setString(6, event.statementKind());
            statement.setString(7, event.status());
            statement.setString(8, event.phase());
            setNullableLong(statement, 9, event.rowCount());
            statement.setString(10, event.sqlState());
            setNullableInteger(statement, 11, event.errorCode());
            statement.setInt(12, event.recorded() ? 1 : 0);
            statement.setString(13, event.exclusionReason());
            statement.setString(14, event.rawSql());
            statement.setString(15, event.replayableSql());
            statement.setString(16, event.createdAt().toString());
            statement.executeUpdate();
        }
    }

    public List<StatementEventRecord> findStatements(String executionId) throws SQLException {
        try (var connection = database.openConnection();
                var statement = connection.prepareStatement("""
                        SELECT * FROM statement_event
                        WHERE execution_id = ? ORDER BY statement_index
                        """)) {
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                var events = new ArrayList<StatementEventRecord>();
                while (rows.next()) {
                    events.add(readStatement(rows));
                }
                return List.copyOf(events);
            }
        }
    }

    private static ExecutionRecord readExecution(ResultSet rows) throws SQLException {
        return new ExecutionRecord(
                rows.getString("execution_id"),
                rows.getString("correlation_id"),
                rows.getString("session_id"),
                rows.getString("connection_fingerprint"),
                rows.getString("source"),
                rows.getString("purpose"),
                rows.getString("sql_text"),
                rows.getString("phase"),
                rows.getString("status"),
                Instant.parse(rows.getString("started_at")),
                parseInstant(rows.getString("completed_at")),
                nullableLong(rows, "affected_row_count"),
                nullableLong(rows, "returned_row_count"),
                rows.getString("sql_state"),
                nullableInteger(rows, "error_code"),
                rows.getString("error_message"),
                rows.getInt("recorded") != 0,
                rows.getString("exclusion_reason"));
    }

    private static StatementEventRecord readStatement(ResultSet rows) throws SQLException {
        return new StatementEventRecord(
                rows.getString("execution_id"),
                rows.getString("session_id"),
                rows.getInt("release_version"),
                rows.getInt("statement_index"),
                nullableLong(rows, "sequence_number"),
                rows.getString("statement_kind"),
                rows.getString("status"),
                rows.getString("phase"),
                nullableLong(rows, "row_count"),
                rows.getString("sql_state"),
                nullableInteger(rows, "error_code"),
                rows.getInt("recorded") != 0,
                rows.getString("exclusion_reason"),
                rows.getString("raw_sql"),
                rows.getString("replayable_sql"),
                Instant.parse(rows.getString("created_at")));
    }

    private static void setNullableLong(
            java.sql.PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableInteger(
            java.sql.PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        var value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rows, String column) throws SQLException {
        var value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static String instantText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant parseInstant(String instant) {
        return instant == null ? null : Instant.parse(instant);
    }

    public record ExecutionRecord(
            String executionId,
            String correlationId,
            String sessionId,
            String connectionFingerprint,
            String source,
            String purpose,
            String sqlText,
            String phase,
            String status,
            Instant startedAt,
            Instant completedAt,
            Long affectedRowCount,
            Long returnedRowCount,
            String sqlState,
            Integer errorCode,
            String errorMessage,
            boolean recorded,
            String exclusionReason) {}

    public record StatementEventRecord(
            String executionId,
            String sessionId,
            int releaseVersion,
            int statementIndex,
            Long sequenceNumber,
            String statementKind,
            String status,
            String phase,
            Long rowCount,
            String sqlState,
            Integer errorCode,
            boolean recorded,
            String exclusionReason,
            String rawSql,
            String replayableSql,
            Instant createdAt) {}
}
