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
import java.util.Objects;

public final class ExecutionRepository {
    private static final java.util.Set<ExecutionStatus> TERMINAL = java.util.Set.of(
            ExecutionStatus.COMPLETED, ExecutionStatus.FAILED,
            ExecutionStatus.CANCELLED, ExecutionStatus.REJECTED);
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
        started(executionId, UUID.randomUUID(), sessionId, connectionFingerprint, source, purpose, sql);
    }

    public void started(UUID executionId, UUID correlationId, String sessionId,
                        String connectionFingerprint, ExecutionSource source,
                        Optional<SqlPurpose> purpose, String sql) throws SQLException {
        var now = Instant.now();
        saveExecution(new ExecutionRecord(executionId.toString(), correlationId.toString(),
                sessionId, connectionFingerprint, source.name(),
                purpose.map(Enum::name).orElse(null), sql, ExecutionStatus.CONNECTING.name(),
                "RUNNING", now, null, null, null, null, null, null, false, null));
    }

    public void connected(UUID executionId, String fingerprint) throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                     "UPDATE execution SET connection_fingerprint = ? WHERE execution_id = ?")) {
            statement.setString(1, fingerprint);
            statement.setString(2, executionId.toString());
            statement.executeUpdate();
        }
    }

    public void queryFinished(UUID executionId, long returnedRows) throws SQLException {
        if (returnedRows < 0) throw new IllegalArgumentException("returnedRows must not be negative");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                     "UPDATE execution SET returned_row_count = ? WHERE execution_id = ?")) {
            statement.setLong(1, returnedRows);
            statement.setString(2, executionId.toString());
            statement.executeUpdate();
        }
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
            statement.setString(1, error.map(value -> value.phase().name()).orElse(status.name()));
            statement.setString(2, status.name());
            statement.setString(3, Instant.now().toString());
            statement.setString(4, error.map(SafeError::sqlState).orElse(null));
            setNullableInteger(statement, 5, error.map(SafeError::errorCode).orElse(null));
            statement.setString(6, error.map(SafeError::message).orElse(null));
            statement.setString(7, executionId.toString());
            statement.executeUpdate();
        }
    }

    public void finish(UUID executionId, List<StatementResult> results,
            ExecutionStatus status, Optional<SafeError> error) throws SQLException {
        long affected = results.stream().filter(StatementResult::committed)
                .mapToLong(StatementResult::rowCount).sum();
        boolean recorded = results.stream().anyMatch(StatementResult::recorded);
        String exclusion = results.stream().map(StatementResult::exclusionReason)
                .filter(Objects::nonNull).findFirst().orElse(null);
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    UPDATE execution SET affected_row_count = ?, recorded = ?, exclusion_reason = ?,
                        phase = ?, status = ?, completed_at = ?, sql_state = ?, error_code = ?, error_message = ?
                    WHERE execution_id = ?
                    """)) {
                statement.setLong(1, affected);
                statement.setInt(2, recorded ? 1 : 0);
                statement.setString(3, exclusion);
                statement.setString(4, error.map(value -> value.phase().name()).orElse(status.name()));
                statement.setString(5, status.name());
                statement.setString(6, Instant.now().toString());
                statement.setString(7, error.map(SafeError::sqlState).orElse(null));
                setNullableInteger(statement, 8, error.map(SafeError::errorCode).orElse(null));
                statement.setString(9, error.map(SafeError::message).orElse(null));
                statement.setString(10, executionId.toString());
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    public Page<ExecutionSummary> search(ExecutionFilter filter, int offset, int limit) throws SQLException {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid history page");
        filter = filter == null ? new ExecutionFilter(null, null, null, null, null, null) : filter;
        var sql = new StringBuilder("SELECT * FROM execution WHERE 1=1");
        var values = new ArrayList<Object>();
        if (filter.sessionId() != null) { sql.append(" AND session_id = ?"); values.add(filter.sessionId()); }
        if (filter.status() != null) { if(TERMINAL.contains(filter.status())){sql.append(" AND status = ?");values.add(filter.status().name());}else{sql.append(" AND status = 'RUNNING' AND phase = ?");values.add(filter.status().name());} }
        if (filter.source() != null) { sql.append(" AND source = ?"); values.add(filter.source().name()); }
        if (filter.purpose() != null) { sql.append(" AND purpose = ?"); values.add(filter.purpose().name()); }
        if (filter.startedAfter() != null) { sql.append(" AND started_at >= ?"); values.add(filter.startedAfter().toString()); }
        if (filter.startedBefore() != null) { sql.append(" AND started_at <= ?"); values.add(filter.startedBefore().toString()); }
        if (filter.recorded() != null) { sql.append(" AND recorded = ?"); values.add(filter.recorded() ? 1 : 0); }
        if (filter.correlationId() != null) { sql.append(" AND correlation_id = ?"); values.add(filter.correlationId().toString()); }
        if (filter.success() != null) { sql.append(filter.success() ? " AND status = 'COMPLETED'" : " AND status <> 'COMPLETED'"); }
        if (filter.kind() != null) { sql.append(" AND EXISTS (SELECT 1 FROM statement_event se WHERE se.execution_id = execution.execution_id AND se.statement_kind = ?)"); values.add(filter.kind().name()); }
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
                ExecutionStatus.valueOf("RUNNING".equals(rows.getString("status"))?rows.getString("phase"):rows.getString("status")),
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

    public int countRunning(String sessionId) throws SQLException {
        try(var connection=database.openConnection();var statement=connection.prepareStatement(
                "SELECT COUNT(*) FROM execution WHERE session_id=? AND status='RUNNING'")){
            statement.setString(1,sessionId);try(var rows=statement.executeQuery()){rows.next();return rows.getInt(1);}
        }
    }

    public void persistStatementFacts(UUID executionId, String sessionId, int releaseVersion,
            List<io.dm7codex.plugin.sql.ParsedStatement> parsed, List<StatementResult> results)
            throws SQLException {
        Objects.requireNonNull(executionId);Objects.requireNonNull(sessionId);Objects.requireNonNull(parsed);Objects.requireNonNull(results);
        if(releaseVersion<1)throw new IllegalArgumentException("invalid release version");
        try(var connection=database.openConnection()){
            connection.setAutoCommit(false);
            try{
                for(var result:results){if(result.kind()!=io.dm7codex.plugin.sql.SqlKind.DDL&&result.kind()!=io.dm7codex.plugin.sql.SqlKind.DML)continue;
                    var statement=parsed.stream().filter(value->value.index()==result.index()).findFirst().orElseThrow();
                    String operationId=UUID.nameUUIDFromBytes((executionId+":"+result.index()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    int updated;
                    try(var update=connection.prepareStatement("""
                            UPDATE statement_event SET execution_id=?, phase=?, row_count=?, sql_state=?, error_code=?,
                              exclusion_reason=COALESCE(?,exclusion_reason)
                            WHERE operation_id=? AND session_id=? AND release_version=?
                            """)){
                        update.setString(1,executionId.toString());update.setString(2,result.error().map(e->e.phase().name()).orElse("LOGGING"));update.setLong(3,result.rowCount());
                        update.setString(4,result.error().map(SafeError::sqlState).orElse(null));setNullableInteger(update,5,result.error().map(SafeError::errorCode).orElse(null));
                        update.setString(6,result.exclusionReason());update.setString(7,operationId);update.setString(8,sessionId);update.setInt(9,releaseVersion);updated=update.executeUpdate();}
                    if(updated==0)try(var insert=connection.prepareStatement("""
                            INSERT INTO statement_event(execution_id,session_id,release_version,statement_index,statement_kind,
                              status,phase,row_count,sql_state,error_code,recorded,exclusion_reason,raw_sql,replayable_sql,created_at)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?)
                            """)){
                        insert.setString(1,executionId.toString());insert.setString(2,sessionId);insert.setInt(3,releaseVersion);insert.setInt(4,result.index());insert.setString(5,result.kind().name());
                        insert.setString(6,result.success()?(result.committed()?"SUCCEEDED":"ROLLED_BACK"):"FAILED");insert.setString(7,result.error().map(e->e.phase().name()).orElse("COMMITTED"));insert.setLong(8,result.rowCount());
                        insert.setString(9,result.error().map(SafeError::sqlState).orElse(null));setNullableInteger(insert,10,result.error().map(SafeError::errorCode).orElse(null));insert.setInt(11,result.recorded()?1:0);
                        insert.setString(12,result.exclusionReason());insert.setString(13,statement.originalSql());insert.setString(14,Instant.now().toString());insert.executeUpdate();}
                }
                connection.commit();
            }catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
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

    public ReleaseView releaseView(String sessionId, int version, int limit) throws SQLException {
        Objects.requireNonNull(sessionId, "sessionId");
        if (version < 1 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid release view");
        int recorded; int excluded; int failed; int total;
        try (var connection = database.openConnection(); var counts = connection.prepareStatement("""
                SELECT COUNT(*) total,
                  SUM(CASE WHEN recorded=1 AND status='SUCCEEDED' THEN 1 ELSE 0 END) recorded_count,
                  SUM(CASE WHEN recorded=0 AND status='SUCCEEDED' THEN 1 ELSE 0 END) excluded_count,
                  SUM(CASE WHEN status<>'SUCCEEDED' THEN 1 ELSE 0 END) failed_count
                FROM statement_event WHERE session_id=? AND release_version=?
                  AND statement_kind IN ('DDL','DML')
                """)) {
            counts.setString(1, sessionId); counts.setInt(2, version);
            try (var rows=counts.executeQuery()) { rows.next(); total=rows.getInt("total");
                recorded=rows.getInt("recorded_count"); excluded=rows.getInt("excluded_count"); failed=rows.getInt("failed_count"); }
        }
        var entries=new ArrayList<ReleaseEntryRecord>();
        try (var connection=database.openConnection(); var statement=connection.prepareStatement("""
                SELECT se.*, e.source, e.purpose FROM statement_event se
                LEFT JOIN execution e ON e.execution_id=se.execution_id
                WHERE se.session_id=? AND se.release_version=? AND se.statement_kind IN ('DDL','DML')
                ORDER BY COALESCE(se.sequence_number, 9223372036854775807), se.created_at, se.statement_index
                LIMIT ?
                """)) {
            statement.setString(1,sessionId); statement.setInt(2,version); statement.setInt(3,limit);
            try(var rows=statement.executeQuery()){while(rows.next()) entries.add(new ReleaseEntryRecord(
                    nullableLong(rows,"sequence_number"),rows.getInt("statement_index"),rows.getString("statement_kind"),
                    rows.getString("status"),rows.getString("source"),rows.getString("purpose"),
                    rows.getInt("recorded")!=0,rows.getString("exclusion_reason"),
                    rows.getString("raw_sql"),Instant.parse(rows.getString("created_at")))) ;}
        }
        return new ReleaseView(recorded,excluded,failed,List.copyOf(entries),total>entries.size());
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

    public record ReleaseEntryRecord(Long sequence, int statementIndex, String kind, String status,
            String source, String purpose, boolean recorded, String exclusionReason,
            String rawSql, Instant createdAt) {}
    public record ReleaseView(int recordedCount, int excludedCount, int failedCount,
            List<ReleaseEntryRecord> entries, boolean truncated) {}
}
