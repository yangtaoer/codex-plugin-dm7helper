package io.dm7codex.plugin.execution;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ExecutionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "dm7-cancellation-closer");
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
        if (scheduleClose) scheduleClose(statement, connection);
    }

    public boolean cancel(UUID executionId) {
        var entry = entries.get(Objects.requireNonNull(executionId));
        if (entry == null) return false;
        Statement statement;
        Connection connection;
        boolean issueCancel = false;
        boolean scheduleClose = false;
        synchronized (entry) {
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
        if (scheduleClose) scheduleClose(statement, connection);
        return true;
    }

    public boolean isCancelled(UUID executionId) {
        var entry = entries.get(executionId);
        if (entry == null) return false;
        synchronized (entry) { return entry.cancelled; }
    }

    public void complete(UUID executionId) { entries.remove(executionId); }

    private static void issueCancel(Statement statement) {
        try { statement.cancel(); } catch (Exception ignored) { }
    }

    private void scheduleClose(Statement statement, Connection connection) {
        closer.schedule(() -> {
            try { statement.close(); } catch (Exception ignored) { }
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
        }, 2, TimeUnit.SECONDS);
    }

    public void cancelAll() { entries.keySet().forEach(this::cancel); }

    public void forceCloseAll() {
        for (var entry : entries.values()) {
            synchronized (entry) {
                if (entry.statement != null) try { entry.statement.close(); } catch (Exception ignored) { }
                if (entry.connection != null) try { entry.connection.close(); } catch (Exception ignored) { }
            }
        }
    }

    public int activeCount() { return entries.size(); }

    @Override public void close() {
        cancelAll();
        forceCloseAll();
        closer.shutdownNow();
        try { closer.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static final class Entry {
        private boolean cancelled;
        private boolean cancelIssued;
        private boolean forceCloseScheduled;
        private Connection connection;
        private Statement statement;
    }
}
