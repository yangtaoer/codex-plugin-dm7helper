package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;

import io.dm7codex.plugin.connection.DmConnectionFactory;
import io.dm7codex.plugin.release.ReleaseLogService;
import io.dm7codex.plugin.release.ReleaseWriteReservation;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SqlKind;
import io.dm7codex.plugin.sql.SqlSecurityPolicy;
import io.dm7codex.plugin.state.ExecutionRepository;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

public final class ExecutionService implements AutoCloseable {
    private final DmConnectionFactory.ConnectionOpener connections;
    private final DmSqlParser parser;
    private final SqlSecurityPolicy security;
    private final ReleaseLogService releaseLog;
    private final ExecutionRepository history;
    private final ExecutionEventBus events;
    private final ExecutionRegistry registry;
    private final ThreadPoolExecutor executor;

    public ExecutionService(DmConnectionFactory factory, DmSqlParser parser, SqlSecurityPolicy security,
                            ReleaseLogService releaseLog, ExecutionRepository history,
                            ExecutionEventBus events, ExecutionRegistry registry) {
        this(opener(factory), parser, security, releaseLog, history, events, registry, 4, 64);
    }

    public ExecutionService(DmConnectionFactory.ConnectionOpener connections, DmSqlParser parser,
                            SqlSecurityPolicy security, ReleaseLogService releaseLog,
                            ExecutionRepository history, ExecutionEventBus events,
                            ExecutionRegistry registry) {
        this(connections, parser, security, releaseLog, history, events, registry, 4, 64);
    }

    public ExecutionService(DmConnectionFactory.ConnectionOpener connections, DmSqlParser parser,
                            SqlSecurityPolicy security, ReleaseLogService releaseLog,
                            ExecutionRepository history, ExecutionEventBus events,
                            ExecutionRegistry registry, int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) throw new IllegalArgumentException("executor bounds must be positive");
        this.connections = Objects.requireNonNull(connections);
        this.parser = Objects.requireNonNull(parser);
        this.security = Objects.requireNonNull(security);
        this.releaseLog = releaseLog;
        this.history = history;
        this.events = Objects.requireNonNull(events);
        this.registry = Objects.requireNonNull(registry);
        this.executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    var thread = new Thread(runnable, "dm7-execution");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public QueryResult query(SessionState session, QueryCommand command) {
        Objects.requireNonNull(session); Objects.requireNonNull(command);
        var statements = parser.parse(command.sql());
        if (statements.size() != 1 || (statements.get(0).kind() != SqlKind.QUERY
                && statements.get(0).kind() != SqlKind.EXPLAIN)) {
            throw new IllegalArgumentException("Query accepts one QUERY or EXPLAIN statement");
        }
        UUID executionId = UUID.randomUUID();
        registry.register(executionId);
        publish(session, executionId, ExecutionStatus.QUEUED);
        try {
            return bounded(() -> queryValidated(session, command, statements, executionId));
        } catch (ExecutionQueueFullException full) {
            registry.complete(executionId);
            publish(session, executionId, ExecutionStatus.REJECTED);
            throw full;
        }
    }

    private QueryResult queryValidated(SessionState session, QueryCommand command,
            List<io.dm7codex.plugin.sql.ParsedStatement> statements, UUID executionId) {
        UUID correlationId = UUID.randomUUID();
        long started = System.nanoTime();
        DmConnectionFactory.ManagedConnection managed = null;
        String fingerprint = "unknown";
        boolean historyStarted = false;
        try {
            publish(session, executionId, ExecutionStatus.CONNECTING);
            managed = connections.open(command.profileId());
            fingerprint = managed.databaseFingerprint();
            if (history != null) {
                history.started(executionId, session.sessionId(), fingerprint, command.source(),
                        Optional.empty(), command.sql());
                historyStarted = true;
                history.progress(executionId, ExecutionStatus.PARSING);
            }
            publish(session, executionId, ExecutionStatus.PARSING);
            var limits = connections.limits(command.profileId());
            int maxRows = Math.min(command.maxRows(), Math.min(limits.maxRows(), MAX_ROWS));
            long maxBytes = Math.min(command.maxBytes(), Math.min(limits.maxBytes(), MAX_BYTES));
            int timeout = Math.min(command.timeoutSeconds(), limits.queryTimeoutSeconds());
            publish(session, executionId, ExecutionStatus.EXECUTING);
            if (history != null) history.progress(executionId, ExecutionStatus.EXECUTING);
            QueryResult successful;
            try (Statement statement = managed.connection().createStatement()) {
                registry.attach(executionId, managed.connection(), statement);
                statement.setQueryTimeout(timeout);
                statement.setMaxRows(maxRows == Integer.MAX_VALUE ? maxRows : maxRows + 1);
                statement.setFetchSize(Math.min(maxRows + 1, 500));
                try (var rows = statement.executeQuery(command.sql())) {
                    var metadata = rows.getMetaData();
                    var labels = uniqueLabels(metadata);
                    var output = new ArrayList<Map<String, Object>>();
                    var budget = new ByteBudget(maxBytes);
                    boolean truncated = false;
                    while (rows.next()) {
                        if (output.size() >= maxRows) { truncated = true; break; }
                        var row = new LinkedHashMap<String, Object>();
                        for (int column = 1; column <= labels.size(); column++) {
                            Object value = boundedValue(rows.getObject(column), budget);
                            row.put(labels.get(column - 1), value);
                            if (budget.exhausted) truncated = true;
                        }
                        output.add(row);
                        if (budget.exhausted) break;
                    }
                    successful = new QueryResult(executionId, labels, output, truncated, output.size(),
                            budget.used, elapsed(started), fingerprint, Optional.empty());
                }
            }
            managed.close();
            managed = null;
            publish(session, executionId, ExecutionStatus.COMPLETED);
            if (history != null) {
                history.statementFinished(executionId, new StatementResult(0,
                        statements.get(0).kind(), true, true, successful.returnedRows(), false,
                        "query_not_release_eligible", "read_only", elapsed(started), Optional.empty()));
                history.terminal(executionId, ExecutionStatus.COMPLETED, Optional.empty());
            }
            return successful;
        } catch (Exception failure) {
            var phase = registry.isCancelled(executionId) ? ExecutionStatus.CANCELLED : ExecutionStatus.FAILED;
            publish(session, executionId, phase);
            if (history != null && historyStarted) try {
                history.terminal(executionId, phase, Optional.of(safe(correlationId, phase, failure)));
            } catch (SQLException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            return new QueryResult(executionId, List.of(), List.of(), false, 0, 0,
                    elapsed(started), fingerprint, Optional.of(safe(correlationId, phase, failure)));
        } finally {
            registry.complete(executionId);
            if (managed != null) try { managed.close(); } catch (Exception ignored) { }
        }
    }

    public ExecutionResult execute(SessionState session, ExecuteCommand command) {
        Objects.requireNonNull(session); Objects.requireNonNull(command);
        var statements = parser.parse(command.script());
        if (statements.isEmpty()) throw new IllegalArgumentException("script contains no statements");
        for (var statement : statements) {
            security.assertNoEmbeddedCredentials(statement);
            if (statement.kind() == SqlKind.QUERY || statement.kind() == SqlKind.EXPLAIN
                    || statement.kind() == SqlKind.TRANSACTION) {
                throw new IllegalArgumentException("Mutation cannot contain query, explain, or transaction control");
            }
        }
        if (command.atomic() && statements.stream().anyMatch(s -> s.kind() != SqlKind.DML)) {
            throw new AtomicDdlNotSupported();
        }
        if (command.purpose().isReleaseEligible() && statements.stream().anyMatch(
                s -> s.kind() == SqlKind.ANONYMOUS_BLOCK || s.kind() == SqlKind.UNKNOWN)) {
            throw new UntrackableMutationException();
        }
        UUID executionId = UUID.randomUUID();
        registry.register(executionId);
        publish(session, executionId, ExecutionStatus.QUEUED);
        try {
            return bounded(() -> executeValidated(session, command, statements, executionId));
        } catch (ExecutionQueueFullException full) {
            registry.complete(executionId);
            publish(session, executionId, ExecutionStatus.REJECTED);
            throw full;
        }
    }

    private ExecutionResult executeValidated(SessionState session, ExecuteCommand command,
            List<io.dm7codex.plugin.sql.ParsedStatement> statements, UUID executionId) {
        UUID correlationId = UUID.randomUUID();
        long started = System.nanoTime();
        var results = new ArrayList<StatementResult>();
        String fingerprint = "unknown";
        boolean historyStarted = false;
        DmConnectionFactory.ManagedConnection managed = null;
        ReleaseWriteReservation reservation = null;
        try {
            publish(session, executionId, ExecutionStatus.CONNECTING);
            managed = connections.open(command.profileId());
            fingerprint = managed.databaseFingerprint();
            if (releaseLog != null) {
                reservation = releaseLog.reserveWritable(session, fingerprint, command.purpose());
            }
            if (history != null) {
                history.started(executionId, session.sessionId(), fingerprint, command.source(),
                        Optional.of(command.purpose()), command.script());
                historyStarted = true;
            }
            publish(session, executionId, ExecutionStatus.PARSING);
            if (history != null) history.progress(executionId, ExecutionStatus.PARSING);
            publish(session, executionId, ExecutionStatus.EXECUTING);
            if (history != null) history.progress(executionId, ExecutionStatus.EXECUTING);
            if (command.atomic()) {
                managed.connection().setAutoCommit(false);
                boolean failed = false;
                for (var parsed : statements) {
                    long statementStarted = System.nanoTime();
                    try (Statement statement = managed.connection().createStatement()) {
                        registry.attach(executionId, managed.connection(), statement);
                        statement.setQueryTimeout(command.timeoutSeconds());
                        long count = Math.max(0, statement.executeUpdate(parsed.originalSql()));
                        results.add(statementResult(parsed, true, false, count, false,
                                exclusion(command, parsed), "plugin_transaction", elapsed(statementStarted), Optional.empty()));
                    } catch (Exception failure) {
                        results.add(statementResult(parsed, false, false, 0, false,
                                exclusion(command, parsed), "plugin_transaction", elapsed(statementStarted),
                                Optional.of(safe(correlationId, ExecutionStatus.EXECUTING, asException(failure)))));
                        failed = true;
                        break;
                    }
                }
                if (failed) {
                    managed.connection().rollback();
                    publish(session, executionId, ExecutionStatus.FAILED);
                    if (history != null) {
                        for (var result : results) history.statementFinished(executionId, result);
                        history.terminal(executionId, ExecutionStatus.FAILED,
                                results.get(results.size() - 1).error());
                    }
                    return new ExecutionResult(executionId, false, ExecutionStatus.FAILED,
                            results, elapsed(started), fingerprint,
                            results.get(results.size() - 1).error());
                }
                publish(session, executionId, ExecutionStatus.COMMITTING);
                if (history != null) history.progress(executionId, ExecutionStatus.COMMITTING);
                managed.connection().commit();
                results = markCommitted(results);
                publish(session, executionId, ExecutionStatus.LOGGING);
                if (history != null) history.progress(executionId, ExecutionStatus.LOGGING);
                if (reservation != null) {
                    for (int i = 0; i < statements.size(); i++) {
                        releaseLog.recordCommitted(reservation, operationId(executionId, statements.get(i)),
                                statements.get(i), statements.get(i).originalSql());
                        if (command.purpose().isReleaseEligible()) results.set(i, markRecorded(results.get(i)));
                    }
                }
            } else {
                managed.connection().setAutoCommit(true);
                boolean anyFailure = false;
                for (var parsed : statements) {
                    long statementStarted = System.nanoTime();
                    try (Statement statement = managed.connection().createStatement()) {
                        registry.attach(executionId, managed.connection(), statement);
                        statement.setQueryTimeout(command.timeoutSeconds());
                        long count = Math.max(0, statement.executeUpdate(parsed.originalSql()));
                        boolean track = command.purpose().isReleaseEligible() && parsed.releaseEligibleKind();
                        if (reservation != null && track) {
                            releaseLog.recordCommitted(reservation, operationId(executionId, parsed),
                                    parsed, parsed.originalSql());
                        }
                        results.add(statementResult(parsed, true, true, count, track,
                                exclusion(command, parsed), parsed.kind() == SqlKind.DDL
                                        ? "database_managed" : "auto_commit",
                                elapsed(statementStarted), Optional.empty()));
                    } catch (Exception failure) {
                        anyFailure = true;
                        results.add(statementResult(parsed, false, false, 0, false,
                                exclusion(command, parsed), parsed.kind() == SqlKind.DDL
                                        ? "database_managed" : "auto_commit",
                                elapsed(statementStarted), Optional.of(safe(correlationId,
                                        ExecutionStatus.EXECUTING, asException(failure)))));
                        if (!command.continueOnError()) break;
                    }
                }
                publish(session, executionId, ExecutionStatus.LOGGING);
                if (history != null) history.progress(executionId, ExecutionStatus.LOGGING);
                if (anyFailure) {
                    publish(session, executionId, ExecutionStatus.FAILED);
                    if (history != null) {
                        for (var result : results) history.statementFinished(executionId, result);
                        history.terminal(executionId, ExecutionStatus.FAILED,
                                results.stream().filter(r -> r.error().isPresent()).findFirst()
                                        .flatMap(StatementResult::error));
                    }
                    return new ExecutionResult(executionId, false, ExecutionStatus.FAILED,
                            results, elapsed(started), fingerprint,
                            results.stream().filter(r -> r.error().isPresent()).findFirst().flatMap(StatementResult::error));
                }
            }
            if (reservation != null) {
                reservation.close();
                reservation = null;
            }
            managed.close();
            managed = null;
            publish(session, executionId, ExecutionStatus.COMPLETED);
            if (history != null) {
                for (var result : results) history.statementFinished(executionId, result);
                history.terminal(executionId, ExecutionStatus.COMPLETED, Optional.empty());
            }
            return new ExecutionResult(executionId, true, ExecutionStatus.COMPLETED, results,
                    elapsed(started), fingerprint, Optional.empty());
        } catch (Exception failure) {
            if (managed != null && command.atomic()) try { managed.connection().rollback(); }
                catch (Exception rollback) { failure.addSuppressed(rollback); }
            var status = registry.isCancelled(executionId) ? ExecutionStatus.CANCELLED : ExecutionStatus.FAILED;
            publish(session, executionId, status);
            if (history != null && historyStarted) try {
                history.terminal(executionId, status,
                        Optional.of(safe(correlationId, status, asException(failure))));
            } catch (SQLException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            return new ExecutionResult(executionId, false, status, results, elapsed(started),
                    fingerprint, Optional.of(safe(correlationId, status, asException(failure))));
        } finally {
            if (reservation != null) try { reservation.close(); } catch (Exception ignored) { }
            registry.complete(executionId);
            if (managed != null) try { managed.close(); } catch (Exception ignored) { }
        }
    }

    public boolean cancel(UUID executionId) { return registry.cancel(executionId); }
    public List<ExecutionEvent> events(String sessionId, long afterSequence) {
        return events.events(sessionId, afterSequence);
    }

    private <T> T bounded(Callable<T> work) {
        try {
            return executor.submit(work).get();
        } catch (RejectedExecutionException rejected) {
            throw new ExecutionQueueFullException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execution wait was interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Execution task failed");
        }
    }

    @Override public void close() {
        executor.shutdownNow();
        registry.close();
    }

    private void publish(SessionState session, UUID executionId, ExecutionStatus status) {
        events.publish(session.sessionId(), executionId, status, Instant.now(), null);
    }

    private static DmConnectionFactory.ConnectionOpener opener(DmConnectionFactory factory) {
        Objects.requireNonNull(factory);
        return new DmConnectionFactory.ConnectionOpener() {
            @Override public DmConnectionFactory.ManagedConnection open(UUID id) throws SQLException {
                return factory.open(id);
            }
            @Override public DmConnectionFactory.ConnectionLimits limits(UUID id) throws SQLException {
                return factory.limits(id);
            }
        };
    }

    private static List<String> uniqueLabels(java.sql.ResultSetMetaData metadata) throws SQLException {
        var labels = new ArrayList<String>();
        var counts = new LinkedHashMap<String, Integer>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String base = metadata.getColumnLabel(i);
            if (base == null || base.isBlank()) base = metadata.getColumnName(i);
            int count = counts.merge(base, 1, Integer::sum);
            labels.add(count == 1 ? base : base + "#" + count);
        }
        return List.copyOf(labels);
    }

    private static Object boundedValue(Object value, ByteBudget budget) throws Exception {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return budget.binary(bytes);
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) { return budget.binary(input); }
            finally { blob.free(); }
        }
        if (value instanceof NClob nclob) {
            try (Reader reader = nclob.getCharacterStream()) { return budget.text(reader); }
            finally { nclob.free(); }
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) { return budget.text(reader); }
            finally { clob.free(); }
        }
        if (value instanceof java.sql.Date || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp || value instanceof TemporalAccessor) {
            return budget.text(value.toString());
        }
        if (value instanceof String text) return budget.text(text);
        if (value instanceof Number || value instanceof Boolean) {
            budget.consume(value.toString().getBytes(StandardCharsets.UTF_8).length);
            return value;
        }
        return budget.text(value.toString());
    }

    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }

    private static SafeError safe(UUID correlation, ExecutionStatus phase, Exception failure) {
        SQLException sql = failure instanceof SQLException value ? value : null;
        boolean restart = failure instanceof io.dm7codex.plugin.connection.DmDriverLoader.DriverIsolationException isolation
                && isolation.restartRequired();
        return new SafeError(correlation, phase,
                sql == null ? "Database operation failed" : "Database operation failed",
                sql == null ? null : sql.getSQLState(), sql == null ? null : sql.getErrorCode(), restart);
    }

    public static String operationId(UUID executionId, io.dm7codex.plugin.sql.ParsedStatement statement) {
        Objects.requireNonNull(executionId); Objects.requireNonNull(statement);
        return UUID.nameUUIDFromBytes((executionId + ":" + statement.index())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static StatementResult statementResult(io.dm7codex.plugin.sql.ParsedStatement parsed,
            boolean success, boolean committed, long rows, boolean recorded, String exclusion,
            String commitBehavior, long elapsed, Optional<SafeError> error) {
        return new StatementResult(parsed.index(), parsed.kind(), success, committed, rows,
                recorded, exclusion, commitBehavior, elapsed, error);
    }

    private static String exclusion(ExecuteCommand command,
            io.dm7codex.plugin.sql.ParsedStatement statement) {
        if (!command.purpose().isReleaseEligible()) return "purpose_" + command.purpose().name().toLowerCase();
        if (!statement.releaseEligibleKind()) return statement.kind() == SqlKind.ANONYMOUS_BLOCK
                ? "anonymous_block_untracked" : "statement_kind_not_release_eligible";
        return null;
    }

    private static ArrayList<StatementResult> markCommitted(List<StatementResult> values) {
        var result = new ArrayList<StatementResult>();
        for (var value : values) result.add(new StatementResult(value.index(), value.kind(),
                value.success(), true, value.rowCount(), value.recorded(), value.exclusionReason(),
                value.commitBehavior(), value.elapsedMillis(), value.error()));
        return result;
    }

    private static StatementResult markRecorded(StatementResult value) {
        return new StatementResult(value.index(), value.kind(), value.success(), value.committed(),
                value.rowCount(), true, null, value.commitBehavior(), value.elapsedMillis(), value.error());
    }

    private static Exception asException(Exception failure) { return failure; }

    private static final class ByteBudget {
        private final long limit;
        private long used;
        private boolean exhausted;
        private ByteBudget(long limit) { this.limit = limit; }
        private void consume(long amount) {
            if (amount < 0 || used > Long.MAX_VALUE - amount || used + amount > limit) exhausted = true;
            else used += amount;
        }
        private String text(String text) {
            var result = new StringBuilder();
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset);
                String chunk = new String(Character.toChars(cp));
                int bytes = chunk.getBytes(StandardCharsets.UTF_8).length;
                if (used > limit - bytes) { exhausted = true; break; }
                result.append(chunk); used += bytes; offset += Character.charCount(cp);
            }
            return result.toString();
        }
        private String text(Reader reader) throws Exception {
            var result = new StringBuilder();
            int first;
            while (!exhausted && (first = reader.read()) >= 0) {
                String chunk;
                if (Character.isHighSurrogate((char) first)) {
                    int second = reader.read();
                    if (second >= 0 && Character.isLowSurrogate((char) second)) {
                        chunk = new String(new char[]{(char) first, (char) second});
                    } else {
                        chunk = "\uFFFD";
                    }
                } else if (Character.isLowSurrogate((char) first)) {
                    chunk = "\uFFFD";
                } else {
                    chunk = String.valueOf((char) first);
                }
                result.append(text(chunk));
            }
            return result.toString();
        }
        private String binary(byte[] bytes) {
            String marker = "base64:";
            int markerBytes = marker.getBytes(StandardCharsets.UTF_8).length;
            if (used > limit - markerBytes) { exhausted = true; return ""; }
            used += markerBytes;
            long available = Math.max(0, limit - used);
            int take = (int) Math.min(bytes.length, Math.min(Integer.MAX_VALUE, available / 4 * 3));
            if (take < bytes.length) exhausted = true;
            String encoded = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, take));
            consume(encoded.getBytes(StandardCharsets.UTF_8).length);
            return marker + encoded;
        }
        private String binary(InputStream input) throws Exception {
            var output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int read;
            long rawLimit = Math.min(Integer.MAX_VALUE, Math.max(0, limit - used) / 4 * 3 + 3);
            while ((read = input.read(buffer)) >= 0) {
                int take = (int) Math.min(read, rawLimit - output.size());
                if (take > 0) output.write(buffer, 0, take);
                if (take < read || output.size() >= rawLimit) { exhausted = true; break; }
            }
            return binary(output.toByteArray());
        }
    }
}
