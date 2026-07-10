package io.dm7codex.plugin.state;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
