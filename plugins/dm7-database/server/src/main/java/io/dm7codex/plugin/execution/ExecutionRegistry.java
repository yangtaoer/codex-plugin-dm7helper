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
        boolean cancelled;
        synchronized (entry) {
            entry.connection = connection;
            entry.statement = statement;
            cancelled = entry.cancelled;
        }
        if (cancelled) cancelAndClose(statement, connection, true);
    }

    public boolean cancel(UUID executionId) {
        var entry = entries.get(Objects.requireNonNull(executionId));
        if (entry == null) return false;
        Statement statement;
        Connection connection;
        synchronized (entry) {
            entry.cancelled = true;
            statement = entry.statement;
            connection = entry.connection;
        }
        if (statement != null) cancelAndClose(statement, connection, false);
        return true;
    }

    public boolean isCancelled(UUID executionId) {
        var entry = entries.get(executionId);
        if (entry == null) return false;
        synchronized (entry) { return entry.cancelled; }
    }

    public void complete(UUID executionId) { entries.remove(executionId); }

    private void cancelAndClose(Statement statement, Connection connection, boolean immediately) {
        try { statement.cancel(); } catch (Exception ignored) { }
        Runnable close = () -> {
            try { statement.close(); } catch (Exception ignored) { }
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
        };
        if (immediately) close.run(); else closer.schedule(close, 2, TimeUnit.SECONDS);
    }

    @Override public void close() { closer.shutdownNow(); }

    private static final class Entry {
        private boolean cancelled;
        private Connection connection;
        private Statement statement;
    }
}
