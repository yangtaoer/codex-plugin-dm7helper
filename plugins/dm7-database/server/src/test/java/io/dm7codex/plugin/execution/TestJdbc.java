package io.dm7codex.plugin.execution;

import io.dm7codex.plugin.connection.DmConnectionFactory;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SqlSecurityPolicy;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TestJdbc {
    static SessionState session() {
        return new SessionState("session", "hash", 1, "unbound", Path.of("active.sql"), Instant.now());
    }

    static ExecutionService service(Opener opener) {
        return new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(), null,
                null, new ExecutionEventBus(32), new ExecutionRegistry());
    }

    static ExecutionService service(DmConnectionFactory.ConnectionOpener opener) {
        return new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(), null,
                null, new ExecutionEventBus(32), new ExecutionRegistry());
    }

    static Statement statement() { return statement(List.of(), List.of()); }

    static Statement statement(List<List<Object>> rows, List<String> labels) {
        return statement(rows, labels, new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
    }
    static Statement statement(List<List<Object>> rows, List<String> labels, AtomicInteger timeout,
            AtomicInteger maxRows, AtomicInteger fetchSize) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Statement) Proxy.newProxyInstance(TestJdbc.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close", "cancel" -> { closed.set(true); yield null; }
                    case "isClosed" -> closed.get();
                    case "executeQuery", "getResultSet" -> resultSet(rows, labels);
                    case "execute" -> true;
                    case "setQueryTimeout" -> { timeout.set((Integer) args[0]); yield null; }
                    case "setMaxRows" -> { maxRows.set((Integer) args[0]); yield null; }
                    case "setFetchSize" -> { fetchSize.set((Integer) args[0]); yield null; }
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    static ResultSet resultSet(List<List<Object>> rows, List<String> labels) {
        AtomicInteger cursor = new AtomicInteger(-1);
        AtomicBoolean closed = new AtomicBoolean();
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                TestJdbc.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> labels.size();
                    case "getColumnLabel", "getColumnName" -> labels.get((Integer) args[0] - 1);
                    case "getColumnType" -> Types.VARCHAR;
                    case "getColumnTypeName" -> "VARCHAR";
                    default -> defaultValue(method.getReturnType());
                });
        return (ResultSet) Proxy.newProxyInstance(TestJdbc.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> cursor.incrementAndGet() < rows.size();
                    case "getMetaData" -> metadata;
                    case "getObject", "getString" -> rows.get(cursor.get()).get((Integer) args[0] - 1);
                    case "close" -> { closed.set(true); yield null; }
                    case "isClosed" -> closed.get();
                    case "wasNull" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    static Connection connection(Statement statement) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(TestJdbc.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> { closed.set(true); yield null; }
                    case "isClosed" -> closed.get();
                    case "createStatement" -> statement;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    static Connection mutationConnection(AtomicInteger executions, int failOn,
            AtomicBoolean committed, AtomicBoolean rolledBack, AtomicBoolean closed) {
        final Connection[] connection = new Connection[1];
        connection[0] = (Connection) Proxy.newProxyInstance(TestJdbc.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> { closed.set(true); yield null; }
                    case "isClosed" -> closed.get();
                    case "setAutoCommit" -> null;
                    case "getAutoCommit" -> true;
                    case "commit" -> { committed.set(true); yield null; }
                    case "rollback" -> { rolledBack.set(true); yield null; }
                    case "createStatement" -> mutationStatement(executions, failOn);
                    default -> defaultValue(method.getReturnType());
                });
        return connection[0];
    }

    static Statement mutationStatement(AtomicInteger executions, int failOn) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Statement) Proxy.newProxyInstance(TestJdbc.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close", "cancel" -> { closed.set(true); yield null; }
                    case "isClosed" -> closed.get();
                    case "executeUpdate" -> {
                        int number = executions.incrementAndGet();
                        if (number == failOn) throw new SQLException("fixture failure", "HY000", 7001);
                        yield 1;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    static final class Opener implements DmConnectionFactory.ConnectionOpener {
        private final AtomicInteger opens = new AtomicInteger();
        private List<List<Object>> rows = List.of();
        private List<String> labels = List.of();
        private Connection lastConnection;
        private int failOn;
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean rolledBack = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private String fingerprint = "fingerprint";
        private DmConnectionFactory.ConnectionLimits limits = new DmConnectionFactory.ConnectionLimits(10_000, 50L * 1024 * 1024, 3600);
        private final AtomicInteger timeout = new AtomicInteger();
        private final AtomicInteger maxRows = new AtomicInteger();
        private final AtomicInteger fetchSize = new AtomicInteger();
        Opener queryRows(List<List<Object>> rows, List<String> labels) {
            this.rows = rows; this.labels = labels; return this;
        }
        Opener failOnStatement(int number) { failOn = number; return this; }
        Opener fingerprint(String value) { fingerprint = value; return this; }
        Opener limits(int rows, long bytes, int seconds) {
            limits = new DmConnectionFactory.ConnectionLimits(rows, bytes, seconds); return this;
        }
        @Override public DmConnectionFactory.ManagedConnection open(UUID profileId) {
            opens.incrementAndGet();
            if (failOn > 0) lastConnection = mutationConnection(executions, failOn, committed, rolledBack, closed);
            else {
                Statement statement = statement(rows, labels, timeout, maxRows, fetchSize);
                lastConnection = connection(statement);
            }
            return new DmConnectionFactory.ManagedConnection(lastConnection, () -> {}, fingerprint);
        }
        int openCount() { return opens.get(); }
        boolean closed() throws Exception { return lastConnection != null && lastConnection.isClosed(); }
        boolean committed() { return committed.get(); }
        boolean rolledBack() { return rolledBack.get(); }
        int executionCount() { return executions.get(); }
        int timeout() { return timeout.get(); }
        int maxRows() { return maxRows.get(); }
        int fetchSize() { return fetchSize.get(); }
        @Override public DmConnectionFactory.ConnectionLimits limits(UUID profileId) { return limits; }
    }
}
