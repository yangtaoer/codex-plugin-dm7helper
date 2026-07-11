package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;

import io.dm7codex.plugin.connection.DmConnectionFactory;
import io.dm7codex.plugin.release.ReleaseLogService;
import io.dm7codex.plugin.release.ReleaseWriteReservation;
import io.dm7codex.plugin.release.ReleaseLogConnectionMismatch;
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
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExecutionService implements AutoCloseable {
    private final DmConnectionFactory.ConnectionOpener connections;
    private final DmSqlParser parser;
    private final SqlSecurityPolicy security;
    private final ReleaseLogService releaseLog;
    private final ExecutionRepository history;
    private final ExecutionEventBus events;
    private final ExecutionRegistry registry;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

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
        ensureOpen();
        Objects.requireNonNull(session); Objects.requireNonNull(command);
        var statements = parser.parse(command.sql());
        if (statements.size() != 1 || (statements.get(0).kind() != SqlKind.QUERY
                && statements.get(0).kind() != SqlKind.EXPLAIN)) {
            throw new IllegalArgumentException("Query accepts one QUERY or EXPLAIN statement");
        }
        UUID executionId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        startHistory(executionId, correlationId, session, "unknown", command.source(),
                Optional.empty(), command.sql());
        registry.register(executionId);
        publish(session, executionId, ExecutionStatus.QUEUED);
        try {
            return bounded(executionId, () -> queryValidated(session, command, statements, executionId, correlationId));
        } catch (ExecutionQueueFullException full) {
            finishRejected(session, executionId, correlationId);
            throw full;
        }
    }

    private QueryResult queryValidated(SessionState session, QueryCommand command,
            List<io.dm7codex.plugin.sql.ParsedStatement> statements, UUID executionId,
            UUID correlationId) {
        long started = System.nanoTime();
        DmConnectionFactory.ManagedConnection managed = null;
        String fingerprint = "unknown";
        boolean historyStarted = history != null;
        ExecutionStatus currentPhase = ExecutionStatus.CONNECTING;
        QueryResult successful = null;
        try {
            publish(session, executionId, ExecutionStatus.CONNECTING);
            checkCancelled(executionId);
            managed = connections.open(command.profileId());
            fingerprint = managed.databaseFingerprint();
            currentPhase = ExecutionStatus.PARSING;
            if (history != null) {
                history.connected(executionId, fingerprint);
                history.progress(executionId, ExecutionStatus.PARSING);
            }
            publish(session, executionId, ExecutionStatus.PARSING);
            var limits = connections.limits(command.profileId());
            int maxRows = Math.min(command.maxRows(), Math.min(limits.maxRows(), MAX_ROWS));
            long maxBytes = Math.min(command.maxBytes(), Math.min(limits.maxBytes(), MAX_BYTES));
            int timeout = Math.min(command.timeoutSeconds(), limits.queryTimeoutSeconds());
            publish(session, executionId, ExecutionStatus.EXECUTING);
            currentPhase = ExecutionStatus.EXECUTING;
            if (history != null) history.progress(executionId, ExecutionStatus.EXECUTING);
            try (Statement statement = managed.connection().createStatement()) {
                checkCancelled(executionId);
                registry.attach(executionId, managed.connection(), statement);
                checkCancelled(executionId);
                statement.setQueryTimeout(timeout);
                statement.setMaxRows(maxRows == Integer.MAX_VALUE ? maxRows : maxRows + 1);
                statement.setFetchSize(Math.min(maxRows + 1, 500));
                try (var rows = statement.executeQuery(command.sql())) {
                    var metadata = rows.getMetaData();
                    var columns = queryColumns(metadata);
                    var output = new ArrayList<Map<String, Object>>();
                    var budget = new ByteBudget(maxBytes);
                    boolean truncated = false;
                    try {
                        for (var column : columns) budget.requireMetadata(column.outputLabel());
                    } catch (BudgetExceeded exceeded) {
                        throw new ResultLimitException();
                    }
                    while (!budget.exhausted && rows.next()) {
                        checkCancelled(executionId);
                        if (output.size() >= maxRows) { truncated = true; break; }
                        var row = new LinkedHashMap<String, Object>();
                        long checkpoint = budget.used;
                        try {
                            for (int column = 1; column <= columns.size(); column++) {
                                Object value = boundedValue(rows.getObject(column), budget);
                                row.put(columns.get(column - 1).outputLabel(), value);
                                if (budget.exhausted) truncated = true;
                            }
                        } catch (BudgetExceeded exceeded) {
                            budget.used = checkpoint;
                            budget.exhausted = true;
                            truncated = true;
                            break;
                        }
                        output.add(row);
                        if (budget.exhausted) break;
                    }
                    successful = new QueryResult(executionId, columns, output, truncated, output.size(),
                            budget.used, elapsed(started), fingerprint, Optional.empty());
                }
            }
            managed.close();
            managed = null;
            return finishQuery(session, executionId, correlationId, successful, started,
                    fingerprint, ExecutionStatus.COMPLETED, Optional.empty());
        } catch (Exception failure) {
            if (managed != null) {
                try { managed.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                managed = null;
            }
            var error = safe(correlationId, currentPhase, failure);
            return finishQuery(session, executionId, correlationId, successful, started,
                    fingerprint, ExecutionStatus.FAILED, Optional.of(error));
        } finally {
            registry.complete(executionId);
            if (managed != null) try { managed.close(); } catch (Exception ignored) { }
        }
    }

    public ExecutionResult execute(SessionState session, ExecuteCommand command) {
        ensureOpen();
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
        UUID correlationId = UUID.randomUUID();
        startHistory(executionId, correlationId, session, "unknown", command.source(),
                Optional.of(command.purpose()), command.script());
        registry.register(executionId);
        publish(session, executionId, ExecutionStatus.QUEUED);
        try {
            return bounded(executionId, () -> executeValidated(session, command, statements, executionId, correlationId));
        } catch (ExecutionQueueFullException full) {
            finishRejected(session, executionId, correlationId);
            throw full;
        }
    }

    private ExecutionResult executeValidated(SessionState session, ExecuteCommand command,
            List<io.dm7codex.plugin.sql.ParsedStatement> statements, UUID executionId,
            UUID correlationId) {
        long started = System.nanoTime();
        var results = new ArrayList<StatementResult>();
        String fingerprint = "unknown";
        boolean historyStarted = history != null;
        boolean databaseCommitted = false;
        ExecutionStatus currentPhase = ExecutionStatus.CONNECTING;
        DmConnectionFactory.ManagedConnection managed = null;
        ReleaseWriteReservation reservation = null;
        try {
            publish(session, executionId, ExecutionStatus.CONNECTING);
            checkCancelled(executionId);
            managed = connections.open(command.profileId());
            fingerprint = managed.databaseFingerprint();
            checkCancelled(executionId);
            if (history != null) history.connected(executionId, fingerprint);
            if (releaseLog != null) {
                reservation = releaseLog.reserveWritable(session, fingerprint, command.purpose());
            }
            checkCancelled(executionId);
            currentPhase = ExecutionStatus.PARSING;
            publish(session, executionId, ExecutionStatus.PARSING);
            if (history != null) history.progress(executionId, ExecutionStatus.PARSING);
            publish(session, executionId, ExecutionStatus.EXECUTING);
            currentPhase = ExecutionStatus.EXECUTING;
            if (history != null) history.progress(executionId, ExecutionStatus.EXECUTING);
            if (command.atomic()) {
                managed.connection().setAutoCommit(false);
                boolean failed = false;
                Exception atomicFailure = null;
                for (var parsed : statements) {
                    checkCancelled(executionId);
                    long statementStarted = System.nanoTime();
                    try (Statement statement = managed.connection().createStatement()) {
                        checkCancelled(executionId);
                        registry.attach(executionId, managed.connection(), statement);
                        checkCancelled(executionId);
                        statement.setQueryTimeout(command.timeoutSeconds());
                        long count = Math.max(0, statement.executeUpdate(parsed.originalSql()));
                        results.add(statementResult(parsed, true, false, count, false,
                                exclusion(command, parsed), "plugin_transaction", elapsed(statementStarted), Optional.empty()));
                        checkCancelled(executionId);
                    } catch (Exception failure) {
                        if (registry.isCancelled(executionId)) throw failure;
                        results.add(statementResult(parsed, false, false, 0, false,
                                exclusion(command, parsed), "plugin_transaction", elapsed(statementStarted),
                                Optional.of(safe(correlationId, ExecutionStatus.EXECUTING, asException(failure)))));
                        failed = true;
                        atomicFailure = failure;
                        break;
                    }
                }
                if (failed) {
                    try { managed.connection().rollback(); }
                    catch (Exception rollback) { atomicFailure.addSuppressed(rollback); }
                    closeForTerminal(reservation, managed, atomicFailure);
                    reservation = null; managed = null;
                    var error = safe(correlationId, ExecutionStatus.EXECUTING, atomicFailure);
                    results.set(results.size() - 1, withError(results.get(results.size() - 1), error));
                    return finishMutation(session, executionId, correlationId, results,
                            started, fingerprint, ExecutionStatus.FAILED, Optional.of(error));
                }
                publish(session, executionId, ExecutionStatus.COMMITTING);
                currentPhase = ExecutionStatus.COMMITTING;
                if (history != null) history.progress(executionId, ExecutionStatus.COMMITTING);
                checkCancelled(executionId);
                managed.connection().commit();
                databaseCommitted = true;
                results = markCommitted(results);
                publish(session, executionId, ExecutionStatus.LOGGING);
                currentPhase = ExecutionStatus.LOGGING;
                if (history != null) history.progress(executionId, ExecutionStatus.LOGGING);
                if (reservation != null) {
                    for (int i = 0; i < statements.size(); i++) {
                        try {
                            releaseLog.recordCommitted(reservation, operationId(executionId, statements.get(i)),
                                    statements.get(i), statements.get(i).originalSql());
                            if (command.purpose().isReleaseEligible()) results.set(i, markRecorded(results.get(i)));
                        } catch (Exception loggingFailure) {
                            closeForTerminal(reservation, managed, loggingFailure);
                            reservation = null;
                            managed = null;
                            var error = safe(correlationId, ExecutionStatus.LOGGING, loggingFailure);
                            results.set(i, markLoggingFailure(results.get(i), error));
                            return finishMutation(session, executionId, correlationId, results,
                                    started, fingerprint, ExecutionStatus.FAILED, Optional.of(error));
                        }
                    }
                }
            } else {
                managed.connection().setAutoCommit(true);
                boolean anyFailure = false;
                Exception terminalFailure = null;
                for (var parsed : statements) {
                    checkCancelled(executionId);
                    long statementStarted = System.nanoTime();
                    try (Statement statement = managed.connection().createStatement()) {
                        checkCancelled(executionId);
                        registry.attach(executionId, managed.connection(), statement);
                        checkCancelled(executionId);
                        statement.setQueryTimeout(command.timeoutSeconds());
                        long count = Math.max(0, statement.executeUpdate(parsed.originalSql()));
                        boolean track = command.purpose().isReleaseEligible() && parsed.releaseEligibleKind();
                        results.add(statementResult(parsed, true, true, count, track,
                                exclusion(command, parsed), parsed.kind() == SqlKind.DDL
                                        ? "database_managed" : "auto_commit",
                                elapsed(statementStarted), Optional.empty()));
                        int resultIndex = results.size() - 1;
                        if (reservation != null && track) {
                            try {
                                releaseLog.recordCommitted(reservation, operationId(executionId, parsed),
                                        parsed, parsed.originalSql());
                            } catch (Exception loggingFailure) {
                                var error = safe(correlationId, ExecutionStatus.LOGGING, loggingFailure);
                                results.set(resultIndex, markLoggingFailure(results.get(resultIndex), error));
                                anyFailure = true;
                                terminalFailure = loggingFailure;
                                if (!command.continueOnError()) break;
                            }
                        }
                        checkCancelled(executionId);
                    } catch (Exception failure) {
                        if (registry.isCancelled(executionId)) throw failure;
                        anyFailure = true;
                        terminalFailure = failure;
                        results.add(statementResult(parsed, false, false, 0, false,
                                exclusion(command, parsed), parsed.kind() == SqlKind.DDL
                                        ? "database_managed" : "auto_commit",
                                elapsed(statementStarted), Optional.of(safe(correlationId,
                                        ExecutionStatus.EXECUTING, asException(failure)))));
                        if (!command.continueOnError()) break;
                    }
                }
                publish(session, executionId, ExecutionStatus.LOGGING);
                currentPhase = ExecutionStatus.LOGGING;
                if (history != null) history.progress(executionId, ExecutionStatus.LOGGING);
                if (anyFailure) {
                    closeForTerminal(reservation, managed, terminalFailure);
                    reservation = null; managed = null;
                    var terminalSafeError = terminalAggregate(correlationId, results,
                            safe(correlationId, currentPhase, terminalFailure));
                    return finishMutation(session, executionId, correlationId, results,
                            started, fingerprint, ExecutionStatus.FAILED,
                            Optional.of(terminalSafeError));
                }
            }
            if (reservation != null) {
                reservation.close();
                reservation = null;
            }
            managed.close();
            managed = null;
            return finishMutation(session, executionId, correlationId, results, started,
                    fingerprint, ExecutionStatus.COMPLETED, Optional.empty());
        } catch (Exception failure) {
            if (managed != null && command.atomic() && !databaseCommitted) try { managed.connection().rollback(); }
                catch (Exception rollback) { failure.addSuppressed(rollback); }
            if (reservation != null) {
                try { reservation.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                reservation = null;
            }
            if (managed != null) {
                try { managed.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                managed = null;
            }
            var error = safe(correlationId, currentPhase, asException(failure));
            return finishMutation(session, executionId, correlationId, results, started,
                    fingerprint, ExecutionStatus.FAILED, Optional.of(error));
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

    private <T> T bounded(UUID executionId, Callable<T> work) {
        java.util.concurrent.Future<T> future;
        var workerCompletion = new java.util.concurrent.CountDownLatch(1);
        try {
            future = executor.submit(() -> {
                try { return work.call(); }
                finally { workerCompletion.countDown(); }
            });
        } catch (RejectedExecutionException rejected) {
            throw new ExecutionQueueFullException();
        }
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            registry.cancel(executionId);
            future.cancel(true);
            Thread.interrupted(); // bounded await below, interrupt restored before returning
            boolean completed = false;
            try { completed = workerCompletion.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { }
            if (!completed) registry.forceClose(executionId);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execution wait was interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Execution task failed");
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        executor.shutdown();
        registry.cancelAll();
        try { executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        registry.forceCloseAll();
        executor.shutdownNow();
        try { executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        registry.closeWithin(deadline);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Execution service is closed");
    }

    private void checkCancelled(UUID executionId) {
        if (registry.isCancelled(executionId)) throw new java.util.concurrent.CancellationException(
                "Execution was cancelled");
    }

    private void publish(SessionState session, UUID executionId, ExecutionStatus status) {
        events.publish(session.sessionId(), executionId, status, Instant.now(), null);
    }

    private void startHistory(UUID executionId, UUID correlationId, SessionState session,
            String fingerprint, ExecutionSource source, Optional<io.dm7codex.plugin.sql.SqlPurpose> purpose,
            String sql) {
        if (history == null) return;
        try {
            history.started(executionId, correlationId, session.sessionId(), fingerprint,
                    source, purpose, sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("Execution history could not be started");
        }
    }

    private void terminalHistory(UUID executionId, ExecutionStatus status, Optional<SafeError> error) {
        if (history == null) return;
        try { history.terminal(executionId, status, error); }
        catch (SQLException failure) { throw new IllegalStateException("Execution history could not be completed"); }
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

    private static List<QueryColumn> queryColumns(java.sql.ResultSetMetaData metadata) throws SQLException {
        var columns = new ArrayList<QueryColumn>();
        var counts = new LinkedHashMap<String, Integer>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String label = metadata.getColumnLabel(i);
            String name = metadata.getColumnName(i);
            String base = label;
            if (base == null || base.isBlank()) base = name;
            int count = counts.merge(base, 1, Integer::sum);
            columns.add(new QueryColumn(count == 1 ? base : base + "#" + count,
                    label == null || label.isBlank() ? name : label, name,
                    metadata.getColumnType(i), metadata.getColumnTypeName(i)));
        }
        return List.copyOf(columns);
    }

    private static Object boundedValue(Object value, ByteBudget budget) throws Exception {
        if (value == null) { budget.requireScalar("null"); return null; }
        if (value instanceof byte[] bytes) return budget.binary(bytes);
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) { return budget.binary(input, -1); }
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
            budget.requireScalar(value.toString());
            return value;
        }
        return budget.text(value.toString());
    }

    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }

    private static SafeError safe(UUID correlation, ExecutionStatus phase, Exception failure) {
        SQLException sql = find(failure, SQLException.class, java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>()));
        var isolation = find(failure,
                io.dm7codex.plugin.connection.DmDriverLoader.DriverIsolationException.class,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        boolean restart = isolation != null && isolation.restartRequired();
        if (find(failure, ReleaseLogConnectionMismatch.class,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())) != null) {
            return new SafeError(correlation, phase, "Release database fingerprint does not match",
                    "DM7APP", 70001, restart);
        }
        if (find(failure, ResultLimitException.class,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())) != null) {
            return new SafeError(correlation, phase, "Result metadata exceeds the byte limit",
                    "DM7APP", 70003, restart);
        }
        return new SafeError(correlation, phase,
                sql == null ? "Database operation failed" : "Database operation failed",
                sql == null ? null : sql.getSQLState(), sql == null ? null : sql.getErrorCode(), restart);
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> type,
            java.util.Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) return null;
        if (type.isInstance(failure)) return type.cast(failure);
        T nested = find(failure.getCause(), type, seen);
        if (nested != null) return nested;
        for (var suppressed : failure.getSuppressed()) {
            nested = find(suppressed, type, seen);
            if (nested != null) return nested;
        }
        return null;
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

    private static StatementResult markLoggingFailure(StatementResult value, SafeError error) {
        return new StatementResult(value.index(), value.kind(), true, true, value.rowCount(),
                false, "release_logging_failed", value.commitBehavior(), value.elapsedMillis(),
                Optional.of(error));
    }

    private static StatementResult withError(StatementResult value, SafeError error) {
        return new StatementResult(value.index(), value.kind(), value.success(), value.committed(),
                value.rowCount(), value.recorded(), value.exclusionReason(), value.commitBehavior(),
                value.elapsedMillis(), Optional.of(error));
    }

    private static SafeError terminalAggregate(UUID correlationId, List<StatementResult> results,
            SafeError fallback) {
        SafeError first = results.stream().flatMap(result -> result.error().stream())
                .findFirst().orElse(fallback);
        boolean restart = fallback.restartRequired() || results.stream()
                .flatMap(result -> result.error().stream()).anyMatch(SafeError::restartRequired);
        return new SafeError(correlationId, first.phase(), first.message(), first.sqlState(),
                first.errorCode(), restart);
    }

    private void persistTerminal(UUID executionId, List<StatementResult> results,
            ExecutionStatus status, Optional<SafeError> error) throws SQLException {
        if (history == null) return;
        history.finish(executionId, results, status, error);
    }

    private ExecutionResult finishMutation(SessionState session, UUID executionId,
            UUID correlationId, List<StatementResult> results, long started, String fingerprint,
            ExecutionStatus desired, Optional<SafeError> desiredError) {
        ExecutionStatus actual = registry.claimTerminal(executionId, desired);
        Optional<SafeError> actualError = desiredError;
        if (actual == ExecutionStatus.CANCELLED) {
            actualError = Optional.of(new SafeError(correlationId, ExecutionStatus.EXECUTING,
                    "Execution was cancelled", "DM7APP", 70004,
                    desiredError.map(SafeError::restartRequired).orElse(false)));
        }
        publish(session, executionId, actual);
        try { persistTerminal(executionId, results, actual, actualError); }
        catch (SQLException ignored) { }
        return new ExecutionResult(executionId, actual == ExecutionStatus.COMPLETED, actual,
                results, elapsed(started), fingerprint, actualError);
    }

    private void finishRejected(SessionState session, UUID executionId, UUID correlationId) {
        ExecutionStatus actual = registry.claimTerminal(executionId, ExecutionStatus.REJECTED);
        SafeError error = actual == ExecutionStatus.CANCELLED
                ? new SafeError(correlationId, ExecutionStatus.CANCELLED,
                        "Execution was cancelled", "DM7APP", 70004, false)
                : new SafeError(correlationId, ExecutionStatus.QUEUED,
                        "Execution queue is full", "DM7APP", 70002, false);
        publish(session, executionId, actual);
        terminalHistory(executionId, actual, Optional.of(error));
        registry.complete(executionId);
    }

    private QueryResult finishQuery(SessionState session, UUID executionId, UUID correlationId,
            QueryResult successful, long started, String fingerprint, ExecutionStatus desired,
            Optional<SafeError> desiredError) {
        ExecutionStatus actual = registry.claimTerminal(executionId, desired);
        Optional<SafeError> actualError = desiredError;
        if (actual == ExecutionStatus.CANCELLED) {
            actualError = Optional.of(new SafeError(correlationId, ExecutionStatus.EXECUTING,
                    "Execution was cancelled", "DM7APP", 70004,
                    desiredError.map(SafeError::restartRequired).orElse(false)));
        }
        publish(session, executionId, actual);
        if (history != null) try {
            if (successful != null) history.queryFinished(executionId, successful.returnedRows());
            history.terminal(executionId, actual, actualError);
        } catch (SQLException ignored) { }
        if (actual == ExecutionStatus.COMPLETED && successful != null) return successful;
        return new QueryResult(executionId, List.of(), List.of(), false, 0, 0,
                elapsed(started), fingerprint, actualError);
    }

    private static void closeForTerminal(ReleaseWriteReservation reservation,
            DmConnectionFactory.ManagedConnection managed, Exception failure) {
        if (reservation != null) try { reservation.close(); } catch (Exception close) { failure.addSuppressed(close); }
        if (managed != null) try { managed.close(); } catch (Exception close) { failure.addSuppressed(close); }
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
        private void requireMetadata(String value) {
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (used > limit - bytes) { exhausted = true; throw new BudgetExceeded(); }
            used += bytes;
        }
        private void requireScalar(String value) { requireMetadata(value); }
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
            int pending = -1;
            int first;
            while (!exhausted && ((first = pending >= 0 ? pending : reader.read()) >= 0)) {
                pending = -1;
                String chunk;
                if (Character.isHighSurrogate((char) first)) {
                    int second = reader.read();
                    if (second >= 0 && Character.isLowSurrogate((char) second)) {
                        chunk = new String(new char[]{(char) first, (char) second});
                    } else {
                        chunk = "\uFFFD";
                        pending = second;
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
            try { return binary(new java.io.ByteArrayInputStream(bytes), bytes.length); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        private String binary(InputStream input, long knownLength) throws Exception {
            String marker = "base64:";
            requireMetadata(marker);
            long remaining = limit - used;
            long rawMax = (remaining / 4) * 3;
            long initialRaw = knownLength >= 0 ? Math.min(knownLength, rawMax)
                    : Math.min(rawMax, 12L * 1024);
            int encodedCapacity = Math.toIntExact(Math.min(16L * 1024 - marker.length(),
                    ((initialRaw + 2) / 3) * 4));
            var result = new StringBuilder(marker.length() + encodedCapacity).append(marker);
            var sink = new AsciiBuilderOutputStream(result);
            var encoder = Base64.getEncoder().wrap(sink);
            byte[] buffer = new byte[12 * 1024];
            long readRaw = 0;
            while (readRaw < rawMax) {
                int request = (int) Math.min(buffer.length, rawMax - readRaw);
                if (request <= 0) break;
                int read = input.read(buffer, 0, request);
                if (read < 0) break;
                encoder.write(buffer, 0, read);
                readRaw += read;
            }
            encoder.close();
            used += sink.written;
            boolean more = input.read() >= 0;
            if (more) exhausted = true;
            return result.toString();
        }
    }

    private static final class AsciiBuilderOutputStream extends java.io.OutputStream {
        private final StringBuilder target;
        private long written;
        private AsciiBuilderOutputStream(StringBuilder target) { this.target = target; }
        @Override public void write(int value) { target.append((char) (value & 0x7f)); written++; }
        @Override public void write(byte[] bytes, int offset, int length) {
            for (int i = offset; i < offset + length; i++) target.append((char) (bytes[i] & 0x7f));
            written += length;
        }
    }

    private static final class BudgetExceeded extends RuntimeException { }
    private static final class ResultLimitException extends RuntimeException { }
}
