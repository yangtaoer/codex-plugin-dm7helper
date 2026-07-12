package io.dm7codex.plugin.execution;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import io.dm7codex.plugin.execution.ExecutionModels.ExecutionStatus;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

public final class ExecutionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "dm7-cancellation-closer");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService cancelCleanup = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "dm7-jdbc-cancel");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService forceCleanup = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "dm7-jdbc-force-close");
        thread.setDaemon(true);
        return thread;
    });

    public boolean register(UUID executionId) {
        return entries.putIfAbsent(Objects.requireNonNull(executionId), new Entry()) == null;
    }

    public void attach(UUID executionId, Connection connection, Statement statement) {
        Objects.requireNonNull(connection); Objects.requireNonNull(statement);
        var entry = entries.computeIfAbsent(Objects.requireNonNull(executionId), ignored -> new Entry());
        boolean issueCancel = false;
        boolean scheduleClose = false;
        synchronized (entry) {
            entry.connection = connection;
            entry.statement = statement;
            if (entry.cancelled && !entry.cancelIssued) {
                entry.cancelIssued = true;
                issueCancel = true;
            }
            if (entry.cancelled && !entry.forceCloseScheduled) {
                entry.forceCloseScheduled = true;
                scheduleClose = true;
            }
        }
        if (issueCancel) issueCancel(statement);
        if (scheduleClose) scheduleClose(executionId);
    }

    public boolean cancel(UUID executionId) {
        return requestCancellation(executionId, CancellationReason.USER);
    }

    public boolean timeout(UUID executionId) {
        return requestCancellation(executionId, CancellationReason.TIMEOUT);
    }

    private boolean requestCancellation(UUID executionId, CancellationReason reason) {
        var entry = entries.get(Objects.requireNonNull(executionId));
        if (entry == null) return false;
        Statement statement;
        Connection connection;
        boolean issueCancel = false;
        boolean scheduleClose = false;
        synchronized (entry) {
            if (entry.terminal) return false;
            if (!entry.cancelled) entry.cancellationReason = reason;
            entry.cancelled = true;
            statement = entry.statement;
            connection = entry.connection;
            if (statement != null && !entry.cancelIssued) {
                entry.cancelIssued = true;
                issueCancel = true;
            }
            if (statement != null && !entry.forceCloseScheduled) {
                entry.forceCloseScheduled = true;
                scheduleClose = true;
            }
        }
        if (issueCancel) issueCancel(statement);
        if (scheduleClose) scheduleClose(executionId);
        return true;
    }

    public boolean isCancelled(UUID executionId) {
        var entry = entries.get(executionId);
        if (entry == null) return false;
        synchronized (entry) { return entry.cancelled; }
    }

    /** Atomically claims terminal completion. False means cancellation won the race. */
    public boolean claimTerminal(UUID executionId) {
        return claimTerminal(executionId, ExecutionStatus.COMPLETED) == ExecutionStatus.COMPLETED;
    }

    public ExecutionStatus claimTerminal(UUID executionId, ExecutionStatus desired) {
        return claimTerminalSnapshot(executionId, desired).status();
    }

    public TerminalClaim claimTerminalSnapshot(UUID executionId, ExecutionStatus desired) {
        var entry = entries.get(executionId);
        if (entry == null) return new TerminalClaim(ExecutionStatus.CANCELLED, false, false);
        synchronized (entry) {
            boolean firstClaim = !entry.terminal;
            if (!entry.terminal) {
                entry.terminal = true;
                entry.terminalStatus = entry.cancellationReason == CancellationReason.TIMEOUT
                        ? ExecutionStatus.FAILED : entry.cancelled ? ExecutionStatus.CANCELLED : desired;
            }
            return new TerminalClaim(entry.terminalStatus,
                    entry.cancellationReason == CancellationReason.TIMEOUT, firstClaim);
        }
    }

    public record TerminalClaim(ExecutionStatus status, boolean timedOut, boolean firstClaim) {}

    public void complete(UUID executionId) { entries.remove(executionId); }

    private void issueCancel(Statement statement) {
        cancelCleanup.execute(() -> { try { statement.cancel(); } catch (Exception ignored) { } });
    }

    private void scheduleClose(UUID executionId) {
        closer.schedule(() -> forceClose(executionId), 2, TimeUnit.SECONDS);
    }

    public void cancelAll() { entries.keySet().forEach(this::cancel); }

    public void forceCloseAll() {
        entries.keySet().forEach(this::forceClose);
    }

    public void forceClose(UUID executionId) {
        var entry = entries.get(executionId);
        if (entry == null) return;
        Statement statement; Connection connection;
        synchronized (entry) {
            if (entry.forceCloseIssued) return;
            statement = entry.statement; connection = entry.connection;
            if (statement == null && connection == null) return;
            entry.forceCloseIssued = true;
        }
        submitForceClose(statement, connection);
    }

    private void submitForceClose(Statement statement, Connection connection) {
        // A vendor Statement.close() may block behind the running query.  Close
        // the connection independently so either resource can release the other.
        if (statement != null) forceCleanup.execute(() -> {
            try { statement.close(); } catch (Exception ignored) { }
        });
        if (connection != null) forceCleanup.execute(() -> {
            try { connection.close(); } catch (Exception ignored) { }
        });
    }

    public int activeCount() { return entries.size(); }

    @Override public void close() {
        closeWithin(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
    }

    void closeWithin(long deadlineNanos) {
        cancelAll();
        forceCloseAll();
        closer.shutdownNow();
        cancelCleanup.shutdownNow();
        forceCleanup.shutdownNow();
        try {
            if (!await(closer, deadlineNanos) || !await(cancelCleanup, deadlineNanos)
                    || !await(forceCleanup, deadlineNanos))
                throw new ExecutionCleanupTimeoutException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExecutionCleanupTimeoutException();
        }
    }

    private static boolean await(java.util.concurrent.ExecutorService executor, long deadline)
            throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        return remaining > 0 && executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    private static final class Entry {
        private boolean cancelled;
        private boolean cancelIssued;
        private boolean forceCloseScheduled;
        private boolean forceCloseIssued;
        private boolean terminal;
        private ExecutionStatus terminalStatus;
        private Connection connection;
        private Statement statement;
        private CancellationReason cancellationReason = CancellationReason.NONE;
    }

    private enum CancellationReason { NONE, USER, TIMEOUT }
}
